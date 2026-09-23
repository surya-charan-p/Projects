package com.financeapp.dto;

import com.financeapp.entity.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateAccountRequest {

    @NotBlank(message = "Account name is required")
    private String accountName;

    @NotNull(message = "Account type is required")
    private AccountType accountType;

    private String currency = "USD";

    // optional starting balance, e.g. an initial deposit
    private java.math.BigDecimal initialBalance;
}
