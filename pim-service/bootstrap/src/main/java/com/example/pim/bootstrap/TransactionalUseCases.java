package com.example.pim.bootstrap;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Wraps a plain application service in a transaction from the outside, so the application layer
 * needs no {@code @Transactional} (or any Spring) annotation. Each use-case call is one transaction.
 */
final class TransactionalUseCases {

    private final TransactionManager transactionManager;

    TransactionalUseCases(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    <T> T readWrite(Class<T> useCase, T service) {
        return wrap(useCase, service, false);
    }

    <T> T readOnly(Class<T> useCase, T service) {
        return wrap(useCase, service, true);
    }

    private <T> T wrap(Class<T> useCase, T service, boolean readOnly) {
        RuleBasedTransactionAttribute attribute = new RuleBasedTransactionAttribute();
        attribute.setReadOnly(readOnly);
        MatchAlwaysTransactionAttributeSource source = new MatchAlwaysTransactionAttributeSource();
        source.setTransactionAttribute(attribute);

        ProxyFactory proxy = new ProxyFactory(service);
        proxy.setInterfaces(useCase);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, source));
        return useCase.cast(proxy.getProxy());
    }
}
