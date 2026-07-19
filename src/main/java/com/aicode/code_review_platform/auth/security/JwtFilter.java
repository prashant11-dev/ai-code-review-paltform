package com.aicode.code_review_platform.auth.security;

import com.aicode.code_review_platform.auth.User;
import com.aicode.code_review_platform.auth.UserRepo;
import com.aicode.code_review_platform.common.utils.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

@Component
public class JwtFilter extends OncePerRequestFilter {

    @Autowired
    private  JwtUtil jwtUtil;

    @Autowired
    private  UserRepo userRepo;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        String authHeader=request.getHeader("Authorization");

        String token=null;
        String email=null;

        if (authHeader != null && authHeader.startsWith("Bearer ")){
            token=authHeader.substring(7);
            email=jwtUtil.extractEmail(token);

        }

        if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {

            User user = userRepo.findByEmail(email).orElse(null);

            if (user != null) {
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                user, null, new ArrayList<>()
                        );

                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        filterChain.doFilter(request, response);
    }
}
