package com.evoting;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EvotingApplication {
    public static void main(String[] args) {
        SpringApplication.run(EvotingApplication.class, args);
    }

    /*@Bean
    public CommandLineRunner printCorrectHash(PasswordEncoder encoder) {
        return args -> {
            System.out.println("=======================================================");
            System.out.println("YOUR PERFECT HASH IS: " + encoder.encode("Admin@12345"));
            System.out.println("=======================================================");
        };
    } */
}
