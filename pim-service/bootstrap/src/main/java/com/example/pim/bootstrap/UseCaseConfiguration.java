package com.example.pim.bootstrap;

import com.example.pim.application.port.in.CreateProductSetUseCase;
import com.example.pim.application.port.in.EditDraftUseCase;
import com.example.pim.application.port.in.OpenDraftUseCase;
import com.example.pim.application.port.in.ProductSetQueries;
import com.example.pim.application.port.in.PublishVersionUseCase;
import com.example.pim.application.port.in.PurgeDiscardedDraftsUseCase;
import com.example.pim.application.port.in.ReviewVersionUseCase;
import com.example.pim.application.port.out.CountryAssignmentRepository;
import com.example.pim.application.port.out.ProductSetEventPublisher;
import com.example.pim.application.port.out.ProductSetRepository;
import com.example.pim.application.port.out.ProductSetVersionRepository;
import com.example.pim.application.port.out.PublicationSequenceGenerator;
import com.example.pim.application.port.out.VersionRetentionRepository;
import com.example.pim.application.service.CreateProductSetService;
import com.example.pim.application.service.EditDraftService;
import com.example.pim.application.service.OpenDraftService;
import com.example.pim.application.service.ProductSetQueryService;
import com.example.pim.application.service.PublishVersionService;
import com.example.pim.application.service.PurgeDiscardedDraftsService;
import com.example.pim.application.service.ReviewVersionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionManager;

import java.time.Clock;

/** Wires the framework-free application services to their adapters, each in a transaction. */
@Configuration(proxyBeanMethods = false)
class UseCaseConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TransactionalUseCases transactionalUseCases(TransactionManager transactionManager) {
        return new TransactionalUseCases(transactionManager);
    }

    @Bean
    CreateProductSetUseCase createProductSetUseCase(TransactionalUseCases tx, ProductSetRepository productSets,
                                                    ProductSetVersionRepository versions) {
        return tx.readWrite(CreateProductSetUseCase.class, new CreateProductSetService(productSets, versions));
    }

    @Bean
    OpenDraftUseCase openDraftUseCase(TransactionalUseCases tx, ProductSetRepository productSets,
                                      ProductSetVersionRepository versions) {
        return tx.readWrite(OpenDraftUseCase.class, new OpenDraftService(productSets, versions));
    }

    @Bean
    EditDraftUseCase editDraftUseCase(TransactionalUseCases tx, ProductSetVersionRepository versions) {
        return tx.readWrite(EditDraftUseCase.class, new EditDraftService(versions));
    }

    @Bean
    ReviewVersionUseCase reviewVersionUseCase(TransactionalUseCases tx, ProductSetVersionRepository versions, Clock clock) {
        return tx.readWrite(ReviewVersionUseCase.class, new ReviewVersionService(versions, clock));
    }

    @Bean
    PublishVersionUseCase publishVersionUseCase(TransactionalUseCases tx, ProductSetVersionRepository versions,
                                                CountryAssignmentRepository countryAssignments,
                                                PublicationSequenceGenerator sequences,
                                                ProductSetEventPublisher events, Clock clock) {
        return tx.readWrite(PublishVersionUseCase.class,
                new PublishVersionService(versions, countryAssignments, sequences, events, clock));
    }

    @Bean
    ProductSetQueries productSetQueries(TransactionalUseCases tx, ProductSetVersionRepository versions,
                                        CountryAssignmentRepository countryAssignments) {
        return tx.readOnly(ProductSetQueries.class, new ProductSetQueryService(versions, countryAssignments));
    }

    @Bean
    PurgeDiscardedDraftsUseCase purgeDiscardedDraftsUseCase(TransactionalUseCases tx,
                                                            VersionRetentionRepository retention, Clock clock) {
        return tx.readWrite(PurgeDiscardedDraftsUseCase.class, new PurgeDiscardedDraftsService(retention, clock));
    }
}
