package com.commercetools.dataimport.generation;

import com.commercetools.sdk.jvm.spring.batch.item.ItemReaderFactory;
import io.sphere.sdk.channels.Channel;
import io.sphere.sdk.channels.queries.ChannelQuery;
import io.sphere.sdk.client.BlockingSphereClient;
import io.sphere.sdk.client.ErrorResponseException;
import io.sphere.sdk.inventory.InventoryEntry;
import io.sphere.sdk.inventory.InventoryEntryDraft;
import io.sphere.sdk.inventory.commands.InventoryEntryCreateCommand;
import io.sphere.sdk.inventory.queries.InventoryEntryQuery;
import io.sphere.sdk.models.ResourceView;
import io.sphere.sdk.products.ProductProjection;
import io.sphere.sdk.products.ProductVariant;
import io.sphere.sdk.products.queries.ProductProjectionQuery;
import io.sphere.sdk.queries.PagedQueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.sphere.sdk.client.SphereClientUtils.blockingWait;
import static io.sphere.sdk.queries.QueryExecutionUtils.queryAll;

@Configuration
public class InventoryEntryGenerationJobConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(InventoryEntryGenerationJobConfiguration.class);

    @Autowired
    private StepBuilderFactory stepBuilderFactory;

    @Autowired
    private BlockingSphereClient sphereClient;

    @Bean
    @JobScope
    public Step inventoryEntryGenerationStep() {
        return stepBuilderFactory.get("inventoryEntryGenerationStep")
                .<ProductProjection, List<InventoryEntryDraft>>chunk(1)
                .reader(reader())
                .processor(processor())
                .writer(writer())
                .faultTolerant()
                .skip(ErrorResponseException.class)
                .skipLimit(1)
                .build();
    }

    private ItemReader<ProductProjection> reader() {
        final Optional<ProductProjection> lastProductWithInventory = findLastProductWithInventory(sphereClient);
        final ProductProjectionQuery baseQuery = ProductProjectionQuery.ofCurrent();
        final ProductProjectionQuery productProjectionQuery = lastProductWithInventory
                .map(productProjection -> baseQuery.withPredicates(product -> product.id().isGreaterThan(productProjection.getId())))
                .orElse(baseQuery);
        return ItemReaderFactory.sortedByIdQueryReader(sphereClient, productProjectionQuery, ResourceView::getId);
    }

    private ItemProcessor<ProductProjection, List<InventoryEntryDraft>> processor() {
        return product -> inventoryEntryListByChannel(product, channelListHolder(sphereClient).getChannels());
    }

    private ItemWriter<List<InventoryEntryDraft>> writer() {
        return entries -> entries.stream()
                .flatMap(Collection::stream)
                .peek(draft -> LOGGER.info("attempting to create inventory entry sku {}, channel {}", draft.getSku(), draft.getSupplyChannel().getId()))
                .map(InventoryEntryCreateCommand::of)
                .forEach(sphereClient::execute);
    }

    private ChannelListHolder channelListHolder(final BlockingSphereClient sphereClient) {
        final ChannelQuery channelQuery = ChannelQuery.of()
                .withPredicates(m -> m.key().isIn(PreferredChannels.CHANNEL_KEYS));
        final List<Channel> channels = blockingWait(queryAll(sphereClient, channelQuery), 5, TimeUnit.MINUTES);
        return new ChannelListHolder(channels);
    }

    private List<InventoryEntryDraft> inventoryEntryListByChannel(final ProductProjection product, final List<Channel> channels) {
        LOGGER.info("Processing product {}", Optional.ofNullable(product).map(ResourceView::getId).orElse("product was null"));
        return channels.stream()
                .flatMap(channel -> product.getAllVariants().stream()
                        .map(productVariant -> createInventoryEntryDraftForProductVariant(channel, productVariant)))
                .collect(Collectors.toList());
    }

    private static InventoryEntryDraft createInventoryEntryDraftForProductVariant(final Channel channel, final ProductVariant productVariant) {
        final Random random = new Random(productVariant.getSku().hashCode() + channel.getKey().hashCode());
        final int bucket = randomInt(random, 0, 99);
        final long quantityOnStock;
        if (bucket > 70) {
            quantityOnStock = randomInt(random, 11, 1000);
        } else if (bucket > 10) {
            quantityOnStock = randomInt(random, 1, 10);
        } else {
            quantityOnStock = 0;
        }
        final String sku = productVariant.getSku();
        return InventoryEntryDraft.of(sku, quantityOnStock).withSupplyChannel(channel);
    }

    private static int randomInt(final Random random, final int min, final int max) {
        return random.nextInt((max - min) + 1) + min;
    }

    private static Optional<ProductProjection> findLastProductWithInventory(final BlockingSphereClient sphereClient) {
        final InventoryEntryQuery inventoryEntryQuery = InventoryEntryQuery.of().withSort(m -> m.lastModifiedAt().sort().desc()).withLimit(1L);
        final Optional<InventoryEntry> inventoryEntryOptional = sphereClient.execute(inventoryEntryQuery).toCompletableFuture().join().head();
        return inventoryEntryOptional.map(inventoryEntry -> {
            final PagedQueryResult<ProductProjection> productProjectionPagedQueryResult
                    = sphereClient.executeBlocking(ProductProjectionQuery.ofCurrent().plusPredicates(product -> product.allVariants().where(m -> m.sku().is(inventoryEntry.getSku()))));
            return productProjectionPagedQueryResult.head();
        }).orElse(Optional.empty());
    }

}