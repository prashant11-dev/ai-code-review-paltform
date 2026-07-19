package com.aicode.code_review_platform.auth;

import com.aicode.code_review_platform.auth.dto.LoginRequest;
import com.aicode.code_review_platform.auth.dto.RegisterRequest;
import com.aicode.code_review_platform.common.utils.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    @Autowired
    private  UserRepo userRepo;

    @Autowired
    private  PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    public void register(RegisterRequest request){
        if(userRepo.findByEmail(request.getEmail()).isPresent()){
            throw new RuntimeException("Email already exists");
        }

        User user= User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .build();
        log.info("Saving user to DB - name: {}, email: {}, hashedPassword: {}", user.getName(), user.getEmail(), user.getPassword());

        userRepo.save(user);
    }

    public String login(LoginRequest request){

        User user = userRepo.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if(!passwordEncoder.matches(request.getPassword(),user.getPassword())){
            throw new RuntimeException("Invalid credentials");
        }

        return jwtUtil.generateToken(user.getEmail());
    }

}
