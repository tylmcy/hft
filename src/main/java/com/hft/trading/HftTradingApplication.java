package com.hft.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * HFT 交易系统主应用
 *
 * A股模拟交易系统
 */
@SpringBootApplication
public class HftTradingApplication {

    public static void main(String[] args) {
        SpringApplication.run(HftTradingApplication.class, args);
    }
}