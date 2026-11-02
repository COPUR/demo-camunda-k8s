package com.bank.bpo.exceptions;

import com.bank.bpo.exceptions.config.SyntheticDataGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The payment exception API: REST over the exception cases that pay-instant-exception runs. */
@SpringBootApplication
public class PaymentExceptionApiApplication {

    public static void main(String[] args) {
        SyntheticDataGuard.require(System.getenv());
        SpringApplication.run(PaymentExceptionApiApplication.class, args);
    }
}
