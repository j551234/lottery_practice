package com.practice.lottery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootTest
class LotteryApplicationTests {

@Test
    void test(){
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    String hashed = encoder.encode("123456");
    System.out.println(hashed);
}



}
