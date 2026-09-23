package com.financeapp.controller;

import com.financeapp.dto.AccountResponse;
import com.financeapp.dto.UserResponse;
import com.financeapp.entity.AccountStatus;
import com.financeapp.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/users")
    public ResponseEntity<Page<UserResponse>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(adminService.getAllUsers(pageable));
    }

    @GetMapping("/users/{userId}/accounts")
    public ResponseEntity<List<AccountResponse>> getUserAccounts(@PathVariable Long userId) {
        return ResponseEntity.ok(adminService.getAccountsForUser(userId));
    }

    @PatchMapping("/users/{userId}/status")
    public ResponseEntity<UserResponse> setUserEnabled(@PathVariable Long userId, @RequestParam boolean enabled) {
        return ResponseEntity.ok(adminService.setUserEnabled(userId, enabled));
    }

    @PatchMapping("/accounts/{accountId}/status")
    public ResponseEntity<AccountResponse> setAccountStatus(@PathVariable Long accountId,
                                                             @RequestParam AccountStatus status) {
        return ResponseEntity.ok(adminService.setAccountStatus(accountId, status));
    }
}
