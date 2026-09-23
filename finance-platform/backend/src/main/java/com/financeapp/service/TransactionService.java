package com.financeapp.service;

import com.financeapp.dto.FundsRequest;
import com.financeapp.dto.TransactionResponse;
import com.financeapp.dto.TransferRequest;
import com.financeapp.entity.*;
import com.financeapp.exception.InvalidTransactionException;
import com.financeapp.exception.ResourceNotFoundException;
import com.financeapp.repository.AccountRepository;
import com.financeapp.repository.TransactionRepository;
import com.financeapp.repository.TransactionSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    /**
     * Transfers funds between two accounts. Both accounts must belong to the
     * authenticated user OR the destination can belong to someone else (peer transfer) -
     * we only verify ownership of the SOURCE account here.
     */
    @Transactional
    public TransactionResponse transfer(Long requestingUserId, TransferRequest request) {
        if (request.getFromAccountNumber().equals(request.getToAccountNumber())) {
            throw new InvalidTransactionException("Cannot transfer to the same account");
        }

        Account from = accountRepository.findByAccountNumber(request.getFromAccountNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Source account not found"));
        Account to = accountRepository.findByAccountNumber(request.getToAccountNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Destination account not found"));

        if (!from.getOwner().getId().equals(requestingUserId)) {
            throw new ResourceNotFoundException("Source account not found");
        }

        Transaction tx = Transaction.builder()
                .reference(UUID.randomUUID().toString())
                .type(TransactionType.TRANSFER)
                .fromAccount(from)
                .toAccount(to)
                .amount(request.getAmount())
                .description(request.getDescription())
                .build();

        validateActive(from);
        validateActive(to);

        if (from.getBalance().compareTo(request.getAmount()) < 0) {
            tx.setStatus(TransactionStatus.FAILED);
            tx.setFailureReason("Insufficient funds");
            transactionRepository.save(tx);
            throw new InvalidTransactionException("Insufficient funds in source account");
        }

        // The atomic core: debit one side, credit the other, in the same DB transaction.
        from.setBalance(from.getBalance().subtract(request.getAmount()));
        to.setBalance(to.getBalance().add(request.getAmount()));
        accountRepository.save(from);
        accountRepository.save(to);

        tx.setStatus(TransactionStatus.COMPLETED);
        transactionRepository.save(tx);

        return TransactionResponse.fromEntity(tx);
    }

    @Transactional
    public TransactionResponse deposit(Long requestingUserId, FundsRequest request) {
        Account account = getOwnedAccountByNumber(request.getAccountNumber(), requestingUserId);
        validateActive(account);

        Transaction tx = Transaction.builder()
                .reference(UUID.randomUUID().toString())
                .type(TransactionType.DEPOSIT)
                .toAccount(account)
                .amount(request.getAmount())
                .description(request.getDescription())
                .status(TransactionStatus.COMPLETED)
                .build();

        account.setBalance(account.getBalance().add(request.getAmount()));
        accountRepository.save(account);
        transactionRepository.save(tx);

        return TransactionResponse.fromEntity(tx);
    }

    @Transactional
    public TransactionResponse withdraw(Long requestingUserId, FundsRequest request) {
        Account account = getOwnedAccountByNumber(request.getAccountNumber(), requestingUserId);
        validateActive(account);

        if (account.getBalance().compareTo(request.getAmount()) < 0) {
            Transaction failed = Transaction.builder()
                    .reference(UUID.randomUUID().toString())
                    .type(TransactionType.WITHDRAWAL)
                    .fromAccount(account)
                    .amount(request.getAmount())
                    .description(request.getDescription())
                    .status(TransactionStatus.FAILED)
                    .failureReason("Insufficient funds")
                    .build();
            transactionRepository.save(failed);
            throw new InvalidTransactionException("Insufficient funds for withdrawal");
        }

        Transaction tx = Transaction.builder()
                .reference(UUID.randomUUID().toString())
                .type(TransactionType.WITHDRAWAL)
                .fromAccount(account)
                .amount(request.getAmount())
                .description(request.getDescription())
                .status(TransactionStatus.COMPLETED)
                .build();

        account.setBalance(account.getBalance().subtract(request.getAmount()));
        accountRepository.save(account);
        transactionRepository.save(tx);

        return TransactionResponse.fromEntity(tx);
    }

    public Page<TransactionResponse> getHistory(Long accountId, Long requestingUserId,
                                                 TransactionType type, TransactionStatus status,
                                                 LocalDateTime from, LocalDateTime to,
                                                 BigDecimal minAmount, BigDecimal maxAmount,
                                                 Pageable pageable) {
        // ownership check
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        if (!account.getOwner().getId().equals(requestingUserId)) {
            throw new ResourceNotFoundException("Account not found");
        }

        Specification<Transaction> spec = Specification
                .where(TransactionSpecifications.belongsToAccount(accountId))
                .and(TransactionSpecifications.hasType(type))
                .and(TransactionSpecifications.hasStatus(status))
                .and(TransactionSpecifications.createdAfter(from))
                .and(TransactionSpecifications.createdBefore(to))
                .and(TransactionSpecifications.minAmount(minAmount))
                .and(TransactionSpecifications.maxAmount(maxAmount));

        return transactionRepository.findAll(spec, pageable)
                .map(TransactionResponse::fromEntity);
    }

    private Account getOwnedAccountByNumber(String accountNumber, Long userId) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        if (!account.getOwner().getId().equals(userId)) {
            throw new ResourceNotFoundException("Account not found");
        }
        return account;
    }

    private void validateActive(Account account) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new InvalidTransactionException(
                    "Account " + account.getAccountNumber() + " is not active (" + account.getStatus() + ")");
        }
    }
}
