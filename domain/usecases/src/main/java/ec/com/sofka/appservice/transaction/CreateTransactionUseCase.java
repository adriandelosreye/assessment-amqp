package ec.com.sofka.appservice.transaction;

import ec.com.sofka.Transaction;
import ec.com.sofka.TransactionStrategy;
import ec.com.sofka.TransactionStrategyFactory;
import ec.com.sofka.appservice.exception.BadRequestException;
import ec.com.sofka.appservice.exception.NotFoundException;
import ec.com.sofka.gateway.AccountRepository;
import ec.com.sofka.gateway.BusMessage;
import ec.com.sofka.gateway.TransactionRepository;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class CreateTransactionUseCase {

    private final TransactionRepository repository;
    private final AccountRepository accountRepository;
    private final TransactionStrategyFactory strategyFactory;
    private final BusMessage busMessage;

    public CreateTransactionUseCase(TransactionRepository repository, AccountRepository accountRepository, TransactionStrategyFactory strategyFactory, BusMessage busMessage) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.strategyFactory = strategyFactory;
        this.busMessage = busMessage;
    }

    public Mono<Transaction> apply(Transaction transaction){
        return accountRepository.findByAccountNumber(transaction.getAccountId())
                .switchIfEmpty(Mono.error(new NotFoundException("Account not found")))
                .flatMap(account -> {
                    TransactionStrategy strategy = strategyFactory.getStrategy(transaction.getType());
                    BigDecimal fee = strategy.calculateFee();
                    BigDecimal balance = strategy.calculateBalance(account.getBalance(), transaction.getAmount());
                    if (balance.compareTo(BigDecimal.ZERO) < 0) {
                        throw new BadRequestException("Insufficient balance for this transaction.");
                    }
                    BigDecimal netAmount = transaction.getAmount().subtract(fee);

                    transaction.setFee(fee);
                    transaction.setNetAmount(netAmount);
                    transaction.setAccountId(account.getId());
                    transaction.setTimestamp(LocalDateTime.now());

                    return repository.create(transaction)
                            .flatMap(savedTransaction -> {
                                busMessage.sendMsg("transaction", "Transaction created with id: " + savedTransaction.getId());
                                account.setBalance(balance);
                                return accountRepository.create(account)
                                        .thenReturn(savedTransaction);
                            });
                });
    }
}
