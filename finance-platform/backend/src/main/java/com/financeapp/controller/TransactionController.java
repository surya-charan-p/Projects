package com.financeapp.controller;

import com.financeapp.dto.FundsRequest;
import com.financeapp.dto.TransactionResponse;
import com.financeapp.dto.TransferRequest;
import com.financeapp.entity.TransactionStatus;
import com.financeapp.entity.TransactionType;
import com.financeapp.entity.User;
import com.financeapp.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(@AuthenticationPrincipal User user,
                                                          @Valid @RequestBody TransferRequest request) {
        return ResponseEntity.ok(transactionService.transfer(user.getId(), request));
    }

    @PostMapping("/deposit")
    public ResponseEntity<TransactionResponse> deposit(@AuthenticationPrincipal User user,
                                                        @Valid @RequestBody FundsRequest request) {
        return ResponseEntity.ok(transactionService.deposit(user.getId(), request));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(@AuthenticationPrincipal User user,
                                                         @Valid @RequestBody FundsRequest request) {
        return ResponseEntity.ok(transactionService.withdraw(user.getId(), request));
    }

    @GetMapping("/account/{accountId}")
    public ResponseEntity<Page<TransactionResponse>> getHistory(
            @AuthenticationPrincipal User user,
            @PathVariable Long accountId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<TransactionResponse> result = transactionService.getHistory(
                accountId, user.getId(), type, status, from, to, minAmount, maxAmount, pageable);

        return ResponseEntity.ok(result);
    }
}
