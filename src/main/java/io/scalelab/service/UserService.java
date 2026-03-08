package io.scalelab.service;

import io.scalelab.dto.CreateUserRequest;
import io.scalelab.dto.UserResponse;
import io.scalelab.entity.User;
import io.scalelab.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @CacheEvict(value = "users", allEntries = true)
    public UserResponse createUser(CreateUserRequest request) {
        long start = System.currentTimeMillis();
        log.info("Creating user with email: {}", request.getEmail());

        User user = new User();
        user.setName(request.getName());
        user.setEmail(request.getEmail());

        User saved = userRepository.save(user);
        log.info("User created with id: {} — took {} ms", saved.getId(), System.currentTimeMillis() - start);

        return mapToResponse(saved);
    }

    @Cacheable(value = "users", key = "'all'")
    public List<UserResponse> getAllUsers() {
        long start = System.currentTimeMillis();
        log.info("Fetching all users");
        // Intentionally fetching ALL users — no pagination, will be slow with large data
        List<User> users = userRepository.findAll();
        log.info("Found {} users — took {} ms", users.size(), System.currentTimeMillis() - start);
        return users.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    @Cacheable(value = "users", key = "#id")
    public UserResponse getUserById(Long id) {
        long start = System.currentTimeMillis();
        log.info("Fetching user by id: {}", id);
        User user = userRepository.findById(id)
                .orElseThrow(() -> new io.scalelab.exception.ResourceNotFoundException("User not found with id: " + id));
        log.info("Fetched user id: {} — took {} ms", id, System.currentTimeMillis() - start);
        return mapToResponse(user);
    }

    private UserResponse mapToResponse(User user) {
        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setName(user.getName());
        response.setEmail(user.getEmail());
        response.setCreatedAt(user.getCreatedAt());
        response.setUpdatedAt(user.getUpdatedAt());
        return response;
    }
}

