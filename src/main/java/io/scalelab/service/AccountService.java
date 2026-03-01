package io.scalelab.service;

import io.scalelab.dto.CreateAccountRequest;
import io.scalelab.dto.AccountResponse;
import io.scalelab.entity.Account;
import io.scalelab.exception.ResourceNotFoundException;
import io.scalelab.repository.AccountRepository;
import io.scalelab.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    public AccountResponse createAccount(CreateAccountRequest request) {
        long start = System.currentTimeMillis();
        log.info("Creating account for user id: {}", request.getUserId());

        // Verify user exists
        userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + request.getUserId()));

        Account account = new Account();
        account.setUserId(request.getUserId());
        account.setBalance(request.getBalance());

        Account saved = accountRepository.save(account);
        log.info("Account created with id: {} for user: {} — took {} ms", saved.getId(), saved.getUserId(), System.currentTimeMillis() - start);

        return mapToResponse(saved);
    }

    public List<AccountResponse> getAccountsByUserId(Long userId) {
        long start = System.currentTimeMillis();
        log.info("Fetching accounts for user id: {}", userId);
        // Intentionally no index on user_id — full table scan
        List<Account> accounts = accountRepository.findByUserId(userId);
        log.info("Found {} accounts for user: {} — took {} ms", accounts.size(), userId, System.currentTimeMillis() - start);
        return accounts.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    private AccountResponse mapToResponse(Account account) {
        AccountResponse response = new AccountResponse();
        response.setId(account.getId());
        response.setUserId(account.getUserId());
        response.setBalance(account.getBalance());
        response.setCreatedAt(account.getCreatedAt());
        response.setUpdatedAt(account.getUpdatedAt());
        return response;
    }
}

