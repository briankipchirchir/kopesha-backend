package com.example.loanapp;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

@Data
@Entity
public class LoanApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String phone;
    private String idNumber;
    private String loanType;
    private int loanAmount;
    private int verificationFee;
    private String status; // PENDING, APPROVED, REJECTED
    private String trackingId;

    @Column(columnDefinition = "TEXT")
    private String mpesaMessage;

    @Column(unique = true)
    private String checkoutRequestID;

    @Column(name = "application_date")
    @Temporal(TemporalType.TIMESTAMP)
    private Date applicationDate;


}
