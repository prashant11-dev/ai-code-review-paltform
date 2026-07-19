package com.aicode.code_review_platform.auth;

import com.aicode.code_review_platform.auth.dto.AuthResponse;
import com.aicode.code_review_platform.auth.dto.LoginRequest;
import com.aicode.code_review_platform.auth.dto.RegisterRequest;
import com.aicode.code_review_platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<String>> register(@Valid  @RequestBody RegisterRequest request) {
        log.info("Register request received - name: {}, email: {}", request.getName(), request.getEmail());

        authService.register(request);

        return ResponseEntity.ok(
                new ApiResponse<>(true, "User registered successfully", null)
        );
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@RequestBody LoginRequest request) {

        String token = authService.login(request);

        return ResponseEntity.ok(
                new ApiResponse<>(true, "Login successful", new AuthResponse(token))
        );
    }

}
