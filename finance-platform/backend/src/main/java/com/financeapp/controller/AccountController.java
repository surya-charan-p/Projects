package com.financeapp.controller;

import com.financeapp.dto.AccountResponse;
import com.financeapp.dto.CreateAccountRequest;
import com.financeapp.entity.User;
import com.financeapp.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@AuthenticationPrincipal User user,
                                                          @Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAccount(user, request));
    }

    @GetMapping
    public ResponseEntity<List<AccountResponse>> getMyAccounts(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(accountService.getAccountsForUser(user.getId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> getAccount(@AuthenticationPrincipal User user, @PathVariable Long id) {
        return ResponseEntity.ok(accountService.getAccountForUser(id, user.getId()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> closeAccount(@AuthenticationPrincipal User user, @PathVariable Long id) {
        accountService.closeAccount(id, user.getId());
        return ResponseEntity.noContent().build();
    }
}
