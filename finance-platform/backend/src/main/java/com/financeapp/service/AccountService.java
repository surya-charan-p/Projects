package com.financeapp.service;

import com.financeapp.dto.AccountResponse;
import com.financeapp.dto.CreateAccountRequest;
import com.financeapp.entity.Account;
import com.financeapp.entity.AccountStatus;
import com.financeapp.entity.User;
import com.financeapp.exception.InvalidTransactionException;
import com.financeapp.exception.ResourceNotFoundException;
import com.financeapp.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Transactional
    public AccountResponse createAccount(User owner, CreateAccountRequest request) {
        Account account = Account.builder()
                .accountNumber(generateUniqueAccountNumber())
                .accountName(request.getAccountName())
                .accountType(request.getAccountType())
                .status(AccountStatus.ACTIVE)
                .balance(request.getInitialBalance() != null ? request.getInitialBalance() : BigDecimal.ZERO)
                .currency(request.getCurrency() != null ? request.getCurrency() : "USD")
                .owner(owner)
                .build();

        accountRepository.save(account);
        return AccountResponse.fromEntity(account);
    }

    public List<AccountResponse> getAccountsForUser(Long userId) {
        return accountRepository.findByOwnerId(userId).stream()
                .map(AccountResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public AccountResponse getAccountForUser(Long accountId, Long userId) {
        Account account = getOwnedAccount(accountId, userId);
        return AccountResponse.fromEntity(account);
    }

    @Transactional
    public void closeAccount(Long accountId, Long userId) {
        Account account = getOwnedAccount(accountId, userId);
        if (account.getBalance().compareTo(BigDecimal.ZERO) != 0) {
            throw new InvalidTransactionException("Cannot close an account with a non-zero balance");
        }
        account.setStatus(AccountStatus.CLOSED);
        accountRepository.save(account);
    }

    private Account getOwnedAccount(Long accountId, Long userId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        if (!account.getOwner().getId().equals(userId)) {
            throw new ResourceNotFoundException("Account not found");
        }
        return account;
    }

    private String generateUniqueAccountNumber() {
        String candidate;
        do {
            // 10-digit numeric account number
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                sb.append(RANDOM.nextInt(10));
            }
            candidate = sb.toString();
        } while (accountRepository.existsByAccountNumber(candidate));
        return candidate;
    }
}
