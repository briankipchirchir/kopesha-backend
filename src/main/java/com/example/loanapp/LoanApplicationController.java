package com.example.loanapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/loans")
@CrossOrigin(
        origins = {
                "http://localhost:3000",
                "https://kopesha.vercel.app"
        },
        allowedHeaders = "*",
        methods = {
                RequestMethod.GET,
                RequestMethod.POST,
                RequestMethod.PUT,
                RequestMethod.DELETE,
                RequestMethod.OPTIONS
        }
)

public class LoanApplicationController {

    @Autowired
    private LoanApplicationRepository repository;

    private Random random = new Random();
    private RestTemplate restTemplate = new RestTemplate();
    private ObjectMapper objectMapper = new ObjectMapper();

    // LOAD MPESA VARIABLES
    private final String consumerKey = EnvConfig.dotenv.get("MPESA_CONSUMER_KEY");
    private final String consumerSecret = EnvConfig.dotenv.get("MPESA_CONSUMER_SECRET");
    private final String shortcode = EnvConfig.dotenv.get("MPESA_SHORTCODE");
    private final String passkey = EnvConfig.dotenv.get("MPESA_PASSKEY");
    private final String callbackUrl = EnvConfig.dotenv.get("MPESA_CALLBACK_URL");

    // Map to store payment statuses
    private static final Map<String, PaymentStatus> paymentStatusMap = new ConcurrentHashMap<>();
    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    // Inner class to track payment details
    private static class PaymentStatus {
        String status; // pending, success, cancelled, failed
        long timestamp;
        String resultDesc;

        PaymentStatus(String status, String resultDesc) {
            this.status = status;
            this.resultDesc = resultDesc;
            this.timestamp = System.currentTimeMillis();
        }
    }

    @PostMapping("/apply")
    public LoanApplication applyLoan(@RequestBody LoanApplication application) {
        // Random loan amount between 10,000 - 250,000
        int loanAmount = random.nextInt(23_000 - 10_000 + 1) + 10_000;
        application.setLoanAmount(loanAmount);

        // Random verification fee between 186 - 199
        int verificationFee = random.nextInt(199 - 186 + 1) + 186;
        application.setVerificationFee(verificationFee);
        application.setStatus("PENDING");

        // Generate random tracking ID: e.g., LON-C123456L9876543
        String trackingId = "LON-C" + (100000 + random.nextInt(900000))
                + "L" + (1000000 + random.nextInt(9000000));
        application.setTrackingId(trackingId);

        return repository.save(application);
    }
    @PostMapping("/stk-push")
    public ResponseEntity<Map<String, Object>> initiateStkPush(@RequestBody StkPushRequest request) {
        try {
            // 1️⃣ Find loan by trackingId
            Optional<LoanApplication> loanOptional = repository.findByTrackingId(request.getTrackingId());
            if (loanOptional.isEmpty()) {
                System.err.println("Loan not found for trackingId: " + request.getTrackingId());
                return ResponseEntity.status(404).body(Map.of(
                        "error", "Loan not found for trackingId: " + request.getTrackingId()
                ));
            }
            LoanApplication loan = loanOptional.get();

            // 2️⃣ Format phone
            String phone = formatPhone(request.getPhone());
            System.out.println("Initiating STK Push for phone: " + phone + ", loan: " + loan.getTrackingId());

            // 3️⃣ Get Access Token
            String auth = consumerKey + ":" + consumerSecret;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());

            HttpHeaders tokenHeaders = new HttpHeaders();
            tokenHeaders.set("Authorization", "Basic " + encodedAuth);

            System.out.println("Requesting MPESA OAuth token...");
            ResponseEntity<Map> tokenRes = restTemplate.exchange(
                    "https://api.safaricom.co.ke/oauth/v1/generate?grant_type=client_credentials",
                    HttpMethod.GET,
                    new HttpEntity<>(tokenHeaders),
                    Map.class
            );
            System.out.println("OAuth token response: " + tokenRes.getBody());

            if (tokenRes.getBody() == null || !tokenRes.getBody().containsKey("access_token")) {
                System.err.println("No access token returned from MPESA OAuth");
                return ResponseEntity.status(500).body(Map.of("error", "Failed to get access token from MPESA"));
            }

            String accessToken = (String) tokenRes.getBody().get("access_token");

            // 4️⃣ Generate password and timestamp
            String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            String password = Base64.getEncoder().encodeToString((shortcode + passkey + timestamp).getBytes());

            // 5️⃣ Build STK Push payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("BusinessShortCode", shortcode);
            payload.put("Password", password);
            payload.put("Timestamp", timestamp);
            payload.put("TransactionType", "CustomerPayBillOnline");
            payload.put("Amount", request.getAmount());
            payload.put("PartyA", phone);
            payload.put("PartyB", shortcode);
            payload.put("PhoneNumber", phone);
            payload.put("CallBackURL", callbackUrl);
            payload.put("AccountReference", "Loan Verification");
            payload.put("TransactionDesc", "Verification Payment");

            System.out.println("STK Push payload: " + payload);

            // 6️⃣ Send STK Push
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> stkRes = restTemplate.postForEntity(
                    "https://api.safaricom.co.ke/mpesa/stkpush/v1/processrequest",
                    new HttpEntity<>(payload, headers),
                    String.class
            );

            String response = stkRes.getBody();
            System.out.println("STK Push raw response: " + response);

            // 7️⃣ Parse response safely
            JsonNode root = objectMapper.readTree(response);
            if (root.has("CheckoutRequestID")) {
                String checkoutRequestID = root.get("CheckoutRequestID").asText();
                loan.setStatus("PENDING");
                loan.setCheckoutRequestID(checkoutRequestID);
                repository.save(loan);

                // Track payment status
                paymentStatusMap.put(checkoutRequestID, new PaymentStatus("pending", "STK Push sent"));

                System.out.println("STK Push successfully initiated for loan " + loan.getTrackingId() + ", CheckoutRequestID: " + checkoutRequestID);

                return ResponseEntity.ok(Map.of(
                        "message", "STK Push sent successfully",
                        "checkoutRequestID", checkoutRequestID
                ));
            } else {
                System.err.println("No CheckoutRequestID returned: " + response);
                return ResponseEntity.status(500).body(Map.of(
                        "error", "STK Push failed, no CheckoutRequestID returned",
                        "rawResponse", response
                ));
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("STK Push failed for loan " + request.getTrackingId() + ": " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "STK Push failed: " + e.getMessage()));
        }
    }



    @GetMapping("/all")
    public List<LoanApplication> getAllLoans() {
        return repository.findAll();
    }

    private String sendStkPush(String phone, int amount) {
        try {
            // 1. Get Access Token
            String auth = consumerKey + ":" + consumerSecret;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());

            HttpHeaders tokenHeaders = new HttpHeaders();
            tokenHeaders.set("Authorization", "Basic " + encodedAuth);

            ResponseEntity<Map> tokenRes = restTemplate.exchange(
                    "https://api.safaricom.co.ke/oauth/v1/generate?grant_type=client_credentials",
                    HttpMethod.GET,
                    new HttpEntity<>(tokenHeaders),
                    Map.class
            );

            String accessToken = (String) tokenRes.getBody().get("access_token");

            // 2. Generate password and timestamp
            String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            String password = Base64.getEncoder().encodeToString(
                    (shortcode + passkey + timestamp).getBytes()
            );

            // 3. Format phone number
            phone = formatPhone(phone);

            // 4. Build STK payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("BusinessShortCode", shortcode);
            payload.put("Password", password);
            payload.put("Timestamp", timestamp);
            payload.put("TransactionType", "CustomerPayBillOnline");
            payload.put("Amount", amount);
            payload.put("PartyA", phone);
            payload.put("PartyB", shortcode);
            payload.put("PhoneNumber", phone);
            payload.put("CallBackURL", callbackUrl);
            payload.put("AccountReference", "Loan Verification");
            payload.put("TransactionDesc", "Verification Payment");

            // 5. Send STK Push
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> stkRes = restTemplate.postForEntity(
                    "https://sandbox.safaricom.co.ke/mpesa/stkpush/v1/processrequest",
                    new HttpEntity<>(payload, headers),
                    String.class
            );

            String response = stkRes.getBody();

            // Extract CheckoutRequestID and initialize status as pending
            try {
                JsonNode root = objectMapper.readTree(response);
                String checkoutRequestID = root.get("CheckoutRequestID").asText();

              // Save CheckoutRequestID to loan
                Optional<LoanApplication> loanOptional = repository.findByCheckoutRequestID(checkoutRequestID);

                if (loanOptional.isPresent()) {
                    LoanApplication loan = loanOptional.get();
                    loan.setCheckoutRequestID(checkoutRequestID);
                    repository.save(loan);
                }


                paymentStatusMap.put(checkoutRequestID, new PaymentStatus("pending", "STK Push sent"));



                System.out.println("STK Push initiated: " + checkoutRequestID);
            } catch (Exception e) {
                System.err.println("Could not extract CheckoutRequestID: " + e.getMessage());
            }

            return response;

        } catch (Exception e) {
            e.printStackTrace();
            return "{\"error\": \"STK Push failed: " + e.getMessage() + "\"}";
        }
    }

    private String formatPhone(String phone) {
        phone = phone.replace("+", "").replace(" ", "");

        if (phone.startsWith("0")) {
            return "254" + phone.substring(1);
        }
        if (phone.startsWith("7")) {
            return "254" + phone;
        }
        if (phone.startsWith("254")) {
            return phone;
        }

        throw new RuntimeException("Invalid phone number format: " + phone);
    }
    @PostMapping("/mpesa/callback")
    public ResponseEntity<Map<String, Object>> mpesaCallback(@RequestBody Map<String, Object> payload) {
        try {
            System.out.println("Callback received: " + payload);

            // Check if payload has Body
            if (payload == null || !payload.containsKey("Body")) {
                System.err.println("Invalid callback payload: missing Body");
                return ResponseEntity.status(400).body(Map.of("error", "Invalid callback payload: missing Body"));
            }

            Map<String, Object> body = (Map<String, Object>) payload.get("Body");

            // Check if Body has stkCallback
            if (body == null || !body.containsKey("stkCallback")) {
                System.err.println("Invalid callback payload: missing stkCallback");
                return ResponseEntity.status(400).body(Map.of("error", "Invalid callback payload: missing stkCallback"));
            }

            Map<String, Object> stkCallback = (Map<String, Object>) body.get("stkCallback");

            // Extract required fields safely
            Integer resultCode = stkCallback.get("ResultCode") instanceof Integer
                    ? (Integer) stkCallback.get("ResultCode")
                    : Integer.parseInt(stkCallback.get("ResultCode").toString());

            String resultDesc = stkCallback.get("ResultDesc") != null
                    ? stkCallback.get("ResultDesc").toString()
                    : "No description";

            String checkoutRequestID = stkCallback.get("CheckoutRequestID") != null
                    ? stkCallback.get("CheckoutRequestID").toString()
                    : null;

            if (checkoutRequestID == null) {
                System.err.println("Invalid callback: missing CheckoutRequestID");
                return ResponseEntity.status(400).body(Map.of("error", "Missing CheckoutRequestID"));
            }

            // Find loan and update status
            Optional<LoanApplication> loanOptional = repository.findByCheckoutRequestID(checkoutRequestID);

            if (loanOptional.isPresent()) {
                LoanApplication loan = loanOptional.get();

                switch (resultCode) {
                    case 0 -> {
                        loan.setStatus("PAID");
                        paymentStatusMap.put(checkoutRequestID, new PaymentStatus("success", resultDesc));
                        System.out.println("Payment successful for loan " + loan.getTrackingId());
                    }
                    case 1032 -> {
                        loan.setStatus("CANCELLED");
                        paymentStatusMap.put(checkoutRequestID, new PaymentStatus("cancelled", resultDesc));
                        System.out.println("Payment cancelled for loan " + loan.getTrackingId());
                    }
                    default -> {
                        loan.setStatus("FAILED");
                        paymentStatusMap.put(checkoutRequestID, new PaymentStatus("failed", resultDesc));
                        System.out.println("Payment failed for loan " + loan.getTrackingId());
                    }
                }

                repository.save(loan);
            } else {
                System.err.println("Loan not found for CheckoutRequestID: " + checkoutRequestID);
            }

            return ResponseEntity.ok(Map.of("message", "Callback processed"));

        } catch (Exception e) {
            System.err.println("Error processing callback: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Callback processing failed"));
        }
    }

    @GetMapping("/mpesa/status/{checkoutRequestID}")
    public ResponseEntity<?> getPaymentStatus(@PathVariable String checkoutRequestID) {
        Optional<LoanApplication> loanOptional =
                loanApplicationRepository.findByCheckoutRequestID(checkoutRequestID);

        if (loanOptional.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "status", "error",
                    "message", "Loan not found"
            ));
        }

        LoanApplication loan = loanOptional.get(); // unwrap Optional

        return ResponseEntity.ok(Map.of(
                "status", loan.getStatus(),
                "message", "Status fetched successfully"
        ));
    }




    // Delete a loan by its tracking ID
    @DeleteMapping("/delete/{trackingId}")
    @CrossOrigin(origins = "https://kopesha.vercel.app")
    public ResponseEntity<Map<String, String>> deleteLoan(@PathVariable String trackingId) {
        Optional<LoanApplication> loanOptional = repository.findByTrackingId(trackingId);

        if (loanOptional.isPresent()) {
            LoanApplication loan = loanOptional.get();
            repository.delete(loan);  // Delete from database

            // Remove from paymentStatusMap if exists
            if (loan.getCheckoutRequestID() != null) {
                paymentStatusMap.remove(loan.getCheckoutRequestID());
            }

            return ResponseEntity.ok(Map.of(
                    "message", "Loan deleted successfully",
                    "trackingId", trackingId
            ));
        } else {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "Loan not found",
                    "trackingId", trackingId
            ));
        }
    }


}