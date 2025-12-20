package com.example.loanapp;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class StkPushRequest {
    private String trackingId;
    private String phone;
    private int amount;
    private int loanAmount;
    private int verificationFee;
}

