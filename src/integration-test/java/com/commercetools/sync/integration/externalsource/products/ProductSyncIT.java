package com.commercetools.sync.integration.externalsource.products;

import com.commercetools.sync.commons.asserts.statistics.AssertionsForStatistics;
import com.commercetools.sync.products.ProductSync;
import com.commercetools.sync.products.ProductSyncOptions;
import com.commercetools.sync.products.ProductSyncOptionsBuilder;
import com.commercetools.sync.products.helpers.ProductSyncStatistics;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.sphere.sdk.categories.Category;
import io.sphere.sdk.categories.CategoryDraft;
import io.sphere.sdk.client.BadGatewayException;
import io.sphere.sdk.client.ConcurrentModificationException;
import io.sphere.sdk.client.ErrorResponseException;
import io.sphere.sdk.client.SphereClient;
import io.sphere.sdk.commands.UpdateAction;
import io.sphere.sdk.models.LocalizedString;
import io.sphere.sdk.models.Reference;
import io.sphere.sdk.models.ResourceIdentifier;
import io.sphere.sdk.models.errors.DuplicateFieldError;
import io.sphere.sdk.products.CategoryOrderHints;
import io.sphere.sdk.products.Product;
import io.sphere.sdk.products.ProductDraft;
import io.sphere.sdk.products.ProductDraftBuilder;
import io.sphere.sdk.products.ProductVariantDraft;
import io.sphere.sdk.products.ProductVariantDraftBuilder;
import io.sphere.sdk.products.attributes.AttributeDraft;
import io.sphere.sdk.products.commands.ProductCreateCommand;
import io.sphere.sdk.products.commands.ProductUpdateCommand;
import io.sphere.sdk.products.commands.updateactions.RemoveFromCategory;
import io.sphere.sdk.products.commands.updateactions.SetAttribute;
import io.sphere.sdk.products.commands.updateactions.SetAttributeInAllVariants;
import io.sphere.sdk.products.commands.updateactions.SetTaxCategory;
import io.sphere.sdk.products.queries.ProductQuery;
import io.sphere.sdk.producttypes.ProductType;
import io.sphere.sdk.queries.PagedQueryResult;
import io.sphere.sdk.queries.QueryPredicate;
import io.sphere.sdk.states.State;
import io.sphere.sdk.states.StateType;
import io.sphere.sdk.taxcategories.TaxCategory;
import io.sphere.sdk.utils.CompletableFutureUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.commercetools.sync.commons.asserts.statistics.AssertionsForStatistics.assertThat;
import static com.commercetools.sync.integration.commons.utils.CategoryITUtils.OLD_CATEGORY_CUSTOM_TYPE_KEY;
import static com.commercetools.sync.integration.commons.utils.CategoryITUtils.OLD_CATEGORY_CUSTOM_TYPE_NAME;
import static com.commercetools.sync.integration.commons.utils.CategoryITUtils.createCategories;
import static com.commercetools.sync.integration.commons.utils.CategoryITUtils.createCategoriesCustomType;
import static com.commercetools.sync.integration.commons.utils.CategoryITUtils.getCategoryDrafts;
import static com.commercetools.sync.integration.commons.utils.CategoryITUtils.getReferencesWithIds;
import static com.commercetools.sync.integration.commons.utils.CategoryITUtils.geResourceIdentifiersWithKeys;
import static com.commercetools.sync.integration.commons.utils.CategoryITUtils.replaceCategoryOrderHintCategoryIdsWithKeys;
import static com.commercetools.sync.integration.commons.utils.ProductITUtils.deleteAllProducts;
import static com.commercetools.sync.integration.commons.utils.ProductITUtils.deleteProductSyncTestData;
import static com.commercetools.sync.integration.commons.utils.ProductTypeITUtils.createProductType;
import static com.commercetools.sync.integration.commons.utils.SphereClientUtils.CTP_TARGET_CLIENT;
import static com.commercetools.sync.integration.commons.utils.StateITUtils.createState;
import static com.commercetools.sync.integration.commons.utils.TaxCategoryITUtils.createTaxCategory;
import static com.commercetools.sync.products.ProductSyncMockUtils.PRODUCT_KEY_1_CHANGED_RESOURCE_PATH;
import static com.commercetools.sync.products.ProductSyncMockUtils.PRODUCT_KEY_1_RESOURCE_PATH;
import static com.commercetools.sync.products.ProductSyncMockUtils.PRODUCT_KEY_2_RESOURCE_PATH;
import static com.commercetools.sync.products.ProductSyncMockUtils.PRODUCT_TYPE_RESOURCE_PATH;
import static com.commercetools.sync.products.ProductSyncMockUtils.createProductDraft;
import static com.commercetools.sync.products.ProductSyncMockUtils.createProductDraftBuilder;
import static com.commercetools.sync.products.ProductSyncMockUtils.createRandomCategoryOrderHints;
import static com.commercetools.sync.products.utils.ProductVariantAttributeUpdateActionUtils.ATTRIBUTE_NOT_IN_ATTRIBUTE_METADATA;
import static com.commercetools.sync.products.utils.ProductVariantUpdateActionUtils.FAILED_TO_BUILD_ATTRIBUTE_UPDATE_ACTION;
import static com.commercetools.tests.utils.CompletionStageUtil.executeBlocking;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class ProductSyncIT {
    private static ProductType productType;
    private static TaxCategory targetTaxCategory;
    private static State targetProductState;
    private static List<Reference<Category>> categoryReferencesWithIds;
    private static Set<ResourceIdentifier<Category>> categoryResourceIdentifiersWithKeys;
    private static CategoryOrderHints categoryOrderHintsWithIds;
    private static CategoryOrderHints categoryOrderHintsWithKeys;
    private ProductSyncOptions syncOptions;
    private Product product;
    private List<String> errorCallBackMessages;
    private List<String> warningCallBackMessages;
    private List<Throwable> errorCallBackExceptions;

    /**
     * Delete all product related test data from the target project. Then creates for the target CTP project price
     * a product type, a tax category, 2 categories, custom types for the categories and a product state.
     */
    @BeforeAll
    static void setup() {
        deleteProductSyncTestData(CTP_TARGET_CLIENT);
        createCategoriesCustomType(OLD_CATEGORY_CUSTOM_TYPE_KEY, Locale.ENGLISH,
            OLD_CATEGORY_CUSTOM_TYPE_NAME, CTP_TARGET_CLIENT);


        final List<CategoryDraft> categoryDrafts = getCategoryDrafts(null, 2);
        final List<Category> categories = createCategories(CTP_TARGET_CLIENT, categoryDrafts);
        categoryReferencesWithIds = getReferencesWithIds(categories);
        categoryResourceIdentifiersWithKeys = geResourceIdentifiersWithKeys(categories);
        categoryOrderHintsWithIds = createRandomCategoryOrderHints(categoryReferencesWithIds);
        categoryOrderHintsWithKeys = replaceCategoryOrderHintCategoryIdsWithKeys(categoryOrderHintsWithIds,
            categories);

        productType = createProductType(PRODUCT_TYPE_RESOURCE_PATH, CTP_TARGET_CLIENT);
        targetTaxCategory = createTaxCategory(CTP_TARGET_CLIENT);
        targetProductState = createState(CTP_TARGET_CLIENT, StateType.PRODUCT_STATE);
    }

    /**
     * Deletes Products and Types from the target CTP project, then it populates target CTP project with product test
     * data.
     */
    @BeforeEach
    void setupTest() {
        clearSyncTestCollections();
        deleteAllProducts(CTP_TARGET_CLIENT);
        syncOptions = buildSyncOptions();

        final ProductDraft productDraft = createProductDraft(PRODUCT_KEY_1_RESOURCE_PATH, productType.toReference(),
            targetTaxCategory.toReference(), targetProductState.toReference(), categoryReferencesWithIds,
            categoryOrderHintsWithIds);

        product = executeBlocking(CTP_TARGET_CLIENT.execute(ProductCreateCommand.of(productDraft)));
    }

    private void clearSyncTestCollections() {
        errorCallBackMessages = new ArrayList<>();
        errorCallBackExceptions = new ArrayList<>();
        warningCallBackMessages = new ArrayList<>();
    }

    private ProductSyncOptions buildSyncOptions() {
        final Consumer<String> warningCallBack = warningMessage -> warningCallBackMessages.add(warningMessage);

        return ProductSyncOptionsBuilder.of(CTP_TARGET_CLIENT)
                                        .errorCallback(this::collectErrors)
                                        .warningCallback(warningCallBack)
                                        .build();
    }

    @AfterAll
    static void tearDown() {
        deleteProductSyncTestData(CTP_TARGET_CLIENT);
    }

    @Test
    void sync_withNewProduct_shouldCreateProduct() {
        final ProductDraft productDraft = createProductDraftBuilder(PRODUCT_KEY_2_RESOURCE_PATH,
            ProductType.referenceOfId(productType.getKey()))
            .taxCategory(null)
            .state(null)
            .build();

        final ProductSync productSync = new ProductSync(syncOptions);
        final ProductSyncStatistics syncStatistics = executeBlocking(productSync.sync(singletonList(productDraft)));

        assertThat(syncStatistics).hasValues(1, 1, 0, 0);
        assertThat(errorCallBackExceptions).isEmpty();
        assertThat(errorCallBackMessages).isEmpty();
        assertThat(warningCallBackMessages).isEmpty();
    }

    @Test
    void sync_withNewProductAndBeforeCreateCallback_shouldCreateProduct() {
        final ProductDraft productDraft = createProductDraftBuilder(PRODUCT_KEY_2_RESOURCE_PATH,
                ProductType.referenceOfId(productType.getKey()))
                .taxCategory(null)
                .state(null)
                .build();

        final String keyPrefix = "callback_";
        final ProductSyncOptions options = ProductSyncOptionsBuilder.of(CTP_TARGET_CLIENT)
                .errorCallback(this::collectErrors)
                .warningCallback(warningMessage -> warningCallBackMessages.add(warningMessage))
                .beforeCreateCallback(draft -> prefixDraftKey(draft, keyPrefix))
                .build();

        final ProductSync productSync = new ProductSync(options);
        final ProductSyncStatistics syncStatistics = executeBlocking(productSync.sync(singletonList(productDraft)));

        assertThat(syncStatistics).hasValues(1, 1, 0, 0);
        assertThat(errorCallBackExceptions).isEmpty();
        assertThat(errorCallBackMessages).isEmpty();
        assertThat(warningCallBackMessages).isEmpty();

        //Query for a product with key prefixed with "callback_" added by the callback

        final String keyWithCallbackPrefix = format("%s%s", keyPrefix, productDraft.getKey());
        final Optional<Product> productOptional = CTP_TARGET_CLIENT
                .execute(ProductQuery.of()
                        .withPredicates(QueryPredicate.of(format("key = \"%s\"", keyWithCallbackPrefix))))
                .toCompletableFuture().join().head();

        assertThat(productOptional).isNotEmpty();
        final Product fetchedProduct = productOptional.get();
        assertThat(fetchedProduct.getKey()).isEqualTo(keyWithCallbackPrefix);
        assertThat(fetchedProduct.getMasterData().getCurrent().getName()).isEqualTo(productDraft.getName());
    }

    @Nonnull
    private static ProductDraft prefixDraftKey(@Nonnull final ProductDraft productDraft, @Nonnull final String prefix) {
        final String newKey = format("%s%s", prefix, productDraft.getKey());
        return ProductDraftBuilder.of(productDraft)
                                  .key(newKey)
                                  .build();
    }

    @Test
    void sync_withNewProductWithExistingSlug_shouldNotCreateProduct() {
        final ProductDraft productDraft = createProductDraftBuilder(PRODUCT_KEY_2_RESOURCE_PATH,
            ProductType.referenceOfId(productType.getKey()))
            .taxCategory(null)
            .state(null)
            .slug(product.getMasterData().getStaged().getSlug())
            .build();

        final ProductSync productSync = new ProductSync(syncOptions);
        final ProductSyncStatistics syncStatistics = executeBlocking(productSync.sync(singletonList(productDraft)));

        assertThat(syncStatistics).hasValues(1, 0, 0, 1);

        final String duplicatedSlug = product.getMasterData().getStaged().getSlug().get(Locale.ENGLISH);
        assertThat(errorCallBackExceptions)
            .hasSize(1)
            .allSatisfy(exception -> {
                assertThat(exception).isExactlyInstanceOf(ErrorResponseException.class);
                final ErrorResponseException errorResponse = ((ErrorResponseException) exception);

                final List<DuplicateFieldError> fieldErrors = errorResponse
                    .getErrors()
                    .stream()
                    .map(sphereError -> {
                        assertThat(sphereError.getCode()).isEqualTo(DuplicateFieldError.CODE);
                        return sphereError.as(DuplicateFieldError.class);
                    })
                    .collect(toList());
                assertThat(fieldErrors).hasSize(1);
                assertThat(fieldErrors).allSatisfy(error -> {
                    assertThat(error.getField()).isEqualTo("slug.en");
                    assertThat(error.getDuplicateValue()).isEqualTo(duplicatedSlug);
                });
            });

        assertThat(errorCallBackMessages)
            .hasSize(1)
            .allSatisfy(errorMessage -> {
                assertThat(errorMessage).contains("\"code\" : \"DuplicateField\"");
                assertThat(errorMessage).contains("\"field\" : \"slug.en\"");
                assertThat(errorMessage).contains(format("\"duplicateValue\" : \"%s\"", duplicatedSlug));
            });

        assertThat(warningCallBackMessages).isEmpty();
    }

    @Test
    void sync_withEqualProduct_shouldNotUpdateProduct() {
        final ProductDraft productDraft =
            createProductDraft(PRODUCT_KEY_1_RESOURCE_PATH, ProductType.referenceOfId(productType.getKey()),
                TaxCategory.referenceOfId(targetTaxCategory.getKey()), State.referenceOfId(targetProductState.getKey()),
                categoryResourceIdentifiersWithKeys, categoryOrderHintsWithKeys);

        final ProductSync productSync = new ProductSync(syncOptions);
        final ProductSyncStatistics syncStatistics =
                executeBlocking(productSync.sync(singletonList(productDraft)));

        assertThat(syncStatistics).hasValues(1, 0, 0, 0);
        assertThat(errorCallBackExceptions).isEmpty();
        assertThat(errorCallBackMessages).isEmpty();
        assertThat(warningCallBackMessages).isEmpty();
    }

    @Test
    void sync_withChangedProduct_shouldUpdateProduct() {
        final ProductDraft productDraft =
            createProductDraft(PRODUCT_KEY_1_CHANGED_RESOURCE_PATH, ProductType.referenceOfId(productType.getKey()),
                TaxCategory.referenceOfId(targetTaxCategory.getKey()), State.referenceOfId(targetProductState.getKey()),
                categoryResourceIdentifiersWithKeys, categoryOrderHintsWithKeys);

        final ProductSync productSync = new ProductSync(syncOptions);
        final ProductSyncStatistics syncStatistics =
                executeBlocking(productSync.sync(singletonList(productDraft)));

        assertThat(syncStatistics).hasValues(1, 0, 1, 0);
        assertThat(errorCallBackExceptions).isEmpty();
        assertThat(errorCallBackMessages).isEmpty();
        assertThat(warningCallBackMessages).isEmpty();
    }

    @Test
    void sync_withChangedProductButConcurrentModificationException_shouldRetryAndUpdateProduct() {
        // preparation
        final SphereClient spyClient = buildClientWithConcurrentModificationUpdate();

        final ProductSyncOptions spyOptions = ProductSyncOptionsBuilder.of(spyClient)
                                                                       .errorCallback(this::collectErrors)
                                                                       .warningCallback(warningMessage ->
                                                                           warningCallBackMessages
                                                                               .add(warningMessage))
                                                                       .build();

        final ProductSync spyProductSync = new ProductSync(spyOptions);

        final ProductDraft productDraft =
            createProductDraft(PRODUCT_KEY_1_CHANGED_RESOURCE_PATH, ProductType.referenceOfId(productType.getKey()),
                TaxCategory.referenceOfId(targetTaxCategory.getKey()), State.referenceOfId(targetProductState.getKey()),
                categoryResourceIdentifiersWithKeys, categoryOrderHintsWithKeys);

        final ProductSyncStatistics syncStatistics =
                executeBlocking(spyProductSync.sync(singletonList(productDraft)));

        assertThat(syncStatistics).hasValues(1, 0, 1, 0);
        assertThat(errorCallBackExceptions).isEmpty();
        assertThat(errorCallBackMessages).isEmpty();
        assertThat(warningCallBackMessages).isEmpty();
    }

    @Nonnull
    private SphereClient buildClientWithConcurrentModificationUpdate() {
        final SphereClient spyClient = spy(CTP_TARGET_CLIENT);

        final ProductUpdateCommand anyProductUpdateCommand = any(ProductUpdateCommand.class);
        when(spyClient.execute(anyProductUpdateCommand))
            .thenReturn(CompletableFutureUtils.exceptionallyCompletedFuture(new ConcurrentModificationException()))
            .thenCallRealMethod();

        return spyClient;
    }

    @Test
    void syncDrafts_WithConcurrentModificationExceptionAndFailedFetch_ShouldFailToReFetchAndUpdate() {
        // preparation
        final SphereClient spyClient = buildClientWithConcurrentModificationUpdateAndFailedFetchOnRetry();

        final ProductSyncOptions spyOptions = ProductSyncOptionsBuilder.of(spyClient)
                                                                       .errorCallback(
                                                                               this::collectErrors)
                                                                       .warningCallback(warningMessage ->
                                                                           warningCallBackMessages
                                                                               .add(warningMessage))
                                                                       .build();

        final ProductSync spyProductSync = new ProductSync(spyOptions);

        final ProductDraft productDraft =
            createProductDraft(PRODUCT_KEY_1_CHANGED_RESOURCE_PATH, ProductType.referenceOfId(productType.getKey()),
                TaxCategory.referenceOfId(targetTaxCategory.getKey()), State.referenceOfId(targetProductState.getKey()),
                categoryResourceIdentifiersWithKeys, categoryOrderHintsWithKeys);

        final ProductSyncStatistics syncStatistics =
            executeBlocking(spyProductSync.sync(singletonList(productDraft)));

        // Test and assertion
        assertThat(syncStatistics).hasValues(1, 0, 0, 1);
        assertThat(errorCallBackMessages).hasSize(1);
        assertThat(errorCallBackExceptions).hasSize(1);

        assertThat(errorCallBackExceptions.get(0).getCause()).isExactlyInstanceOf(BadGatewayException.class);
        assertThat(errorCallBackMessages.get(0)).contains(
            format("Failed to update Product with key: '%s'. Reason: Failed to fetch from CTP while retrying "
                + "after concurrency modification.", productDraft.getKey()));
    }

    @Nonnull
    private SphereClient buildClientWithConcurrentModificationUpdateAndFailedFetchOnRetry() {
        final SphereClient spyClient = spy(CTP_TARGET_CLIENT);

        final ProductUpdateCommand anyProductUpdateCommand = any(ProductUpdateCommand.class);
        when(spyClient.execute(anyProductUpdateCommand))
            .thenReturn(CompletableFutureUtils.exceptionallyCompletedFuture(new ConcurrentModificationException()))
            .thenCallRealMethod();

        final ProductQuery anyProductQuery = any(ProductQuery.class);
        when(spyClient.execute(anyProductQuery))
            .thenCallRealMethod() // cache product keys
            .thenCallRealMethod() // Call real fetch on fetching matching products
            .thenReturn(CompletableFutureUtils.exceptionallyCompletedFuture(new BadGatewayException()));

        return spyClient;
    }

    @Test
    void syncDrafts_WithConcurrentModificationExceptionAndUnexpectedDelete_ShouldFailToReFetchAndUpdate() {
        // preparation
        final SphereClient spyClient = buildClientWithConcurrentModificationUpdateAndNotFoundFetchOnRetry();

        final ProductSyncOptions spyOptions = ProductSyncOptionsBuilder.of(spyClient)
                                                                       .errorCallback(this::collectErrors)
                                                                       .warningCallback(warningMessage ->
                                                                           warningCallBackMessages
                                                                               .add(warningMessage))
                                                                       .build();

        final ProductSync spyProductSync = new ProductSync(spyOptions);

        final ProductDraft productDraft =
            createProductDraft(PRODUCT_KEY_1_CHANGED_RESOURCE_PATH, ProductType.referenceOfId(productType.getKey()),
                TaxCategory.referenceOfId(targetTaxCategory.getKey()), State.referenceOfId(targetProductState.getKey()),
                categoryResourceIdentifiersWithKeys, categoryOrderHintsWithKeys);

        final ProductSyncStatistics syncStatistics =
            executeBlocking(spyProductSync.sync(singletonList(productDraft)));

        // Test and assertion
        AssertionsForStatistics.assertThat(syncStatistics).hasValues(1, 0, 0, 1);
        assertThat(errorCallBackMessages).hasSize(1);
        assertThat(errorCallBackExceptions).hasSize(1);

        assertThat(errorCallBackMessages.get(0)).contains(
            format("Failed to update Product with key: '%s'. Reason: Not found when attempting to fetch while"
                + " retrying after concurrency modification.", productDraft.getKey()));
    }

    @Nonnull
    private SphereClient buildClientWithConcurrentModificationUpdateAndNotFoundFetchOnRetry() {
        final SphereClient spyClient = spy(CTP_TARGET_CLIENT);

        final ProductUpdateCommand anyProductUpdateCommand = any(ProductUpdateCommand.class);
        when(spyClient.execute(anyProductUpdateCommand))
            .thenReturn(CompletableFutureUtils.exceptionallyCompletedFuture(new ConcurrentModificationException()))
            .thenCallRealMethod();

        final ProductQuery anyProductQuery = any(ProductQuery.class);

        when(spyClient.execute(anyProductQuery))
            .thenCallRealMethod() // cache product keys
            .thenCallRealMethod() // Call real fetch on fetching matching products
            .thenReturn(CompletableFuture.completedFuture(PagedQueryResult.empty()));

        return spyClient;
    }

    @Test
    void sync_withMultipleBatchSyncing_ShouldSync() {
        // Prepare existing products with keys: productKey1, productKey2, productKey3.
        final ProductDraft key2Draft = createProductDraft(PRODUCT_KEY_2_RESOURCE_PATH,
            productType.toReference(), targetTaxCategory.toReference(), targetProductState.toReference(),
            categoryReferencesWithIds, product.getMasterData().getStaged().getCategoryOrderHints());
        executeBlocking(CTP_TARGET_CLIENT.execute(ProductCreateCommand.of(key2Draft)));

        final ProductDraft key3Draft = createProductDraftBuilder(PRODUCT_KEY_2_RESOURCE_PATH, productType.toReference())
            .categories(new ArrayList<>())
            .categoryOrderHints(CategoryOrderHints.of(new HashMap<>()))
            .key("productKey3")
            .slug(LocalizedString.of(Locale.ENGLISH, "slug3"))
            .masterVariant(ProductVariantDraftBuilder.of().key("v3").build())
            .taxCategory(TaxCategory.referenceOfId(targetTaxCategory.getId()))
            .build();
        executeBlocking(CTP_TARGET_CLIENT.execute(ProductCreateCommand.of(key3Draft)));


        // Prepare batches from external source
        final ProductDraft productDraft = createProductDraft(PRODUCT_KEY_1_CHANGED_RESOURCE_PATH,
            ProductType.reference(productType.getKey()), TaxCategory.referenceOfId(targetTaxCategory.getKey()),
            State.referenceOfId(targetProductState.getKey()), categoryResourceIdentifiersWithKeys,
            categoryOrderHintsWithKeys);

        final List<ProductDraft> batch1 = new ArrayList<>();
        batch1.add(productDraft);

        final ProductDraft key4Draft = createProductDraftBuilder(PRODUCT_KEY_2_RESOURCE_PATH,
            ProductType.referenceOfId(productType.getKey()))
            .taxCategory(null)
            .state(null)
            .categories(new ArrayList<>())
            .categoryOrderHints(CategoryOrderHints.of(new HashMap<>()))
            .key("productKey4")
            .slug(LocalizedString.of(Locale.ENGLISH, "slug4"))
            .masterVariant(ProductVariantDraftBuilder.of().key("v4").sku("sku4").build())
            .build();

        final List<ProductDraft> batch2 = new ArrayList<>();
        batch2.add(key4Draft);

        final ProductDraft key3DraftNewSlug = createProductDraftBuilder(PRODUCT_KEY_2_RESOURCE_PATH,
            ProductType.referenceOfId(productType.getKey()))
            .taxCategory(null)
            .state(null)
            .categories(new ArrayList<>())
            .categoryOrderHints(CategoryOrderHints.of(new HashMap<>()))
            .key("productKey3")
            .slug(LocalizedString.of(Locale.ENGLISH, "newSlug"))
            .masterVariant(ProductVariantDraftBuilder.of().key("v3").sku("sku3").build())
            .build();

        final List<ProductDraft> batch3 = new ArrayList<>();
        batch3.add(key3DraftNewSlug);

        final ProductSync productSync = new ProductSync(syncOptions);
        final ProductSyncStatistics syncStatistics =
            executeBlocking(productSync.sync(batch1)
                                       .thenCompose(result -> productSync.sync(batch2))
                                       .thenCompose(result -> productSync.sync(batch3)));

        assertThat(syncStatistics).hasValues(3, 1, 2, 0);
        assertThat(errorCallBackExceptions).isEmpty();
        assertThat(errorCallBackMessages).isEmpty();
        assertThat(warningCallBackMessages).isEmpty();
    }

    @Test
    void sync_withSingleBatchSyncing_ShouldSync() {
        // Prepare batches from external source
        final ProductDraft productDraft = createProductDraft(PRODUCT_KEY_1_CHANGED_RESOURCE_PATH,
            ProductType.referenceOfId(productType.getKey()), TaxCategory.referenceOfId(targetTaxCategory.getKey()),
            State.referenceOfId(targetProductState.getKey()), categoryResourceIdentifiersWithKeys,
            categoryOrderHintsWithKeys);

        final ProductDraft key3Draft = createProductDraftBuilder(PRODUCT_KEY_2_RESOURCE_PATH,
            ProductType.referenceOfId(productType.getKey()))
            .taxCategory(TaxCategory.referenceOfId(targetTaxCategory.getKey()))
            .state(State.referenceOfId(targetProductState.getKey()))
            .categories(new ArrayList<>())
            .categoryOrderHints(CategoryOrderHints.of(new HashMap<>()))
            .key("productKey3")
            .slug(LocalizedString.of(Locale.ENGLISH, "slug3"))
            .masterVariant(ProductVariantDraftBuilder.of().key("mv3").sku("sku3").build())
            .build();

        final ProductDraft key4Draft = createProductDraftBuilder(PRODUCT_KEY_2_RESOURCE_PATH,
            ProductType.referenceOfId(productType.getKey()))
            .taxCategory(TaxCategory.referenceOfId(targetTaxCategory.getKey()))
            .state(State.referenceOfId(targetProductState.getKey()))
            .categories(new ArrayList<>())
            .categoryOrderHints(CategoryOrderHints.of(new HashMap<>()))
            .key("productKey4")
            .slug(LocalizedString.of(Locale.ENGLISH, "slug4"))
            .masterVariant(ProductVariantDraftBuilder.of().key("mv4").sku("sku4").build())
            .build();

        final ProductDraft key5Draft = createProductDraftBuilder(PRODUCT_KEY_2_RESOURCE_PATH,
            ProductType.referenceOfId(productType.getKey()))
            .taxCategory(TaxCategory.referenceOfId(targetTaxCategory.getKey()))
            .state(State.referenceOfId(targetProductState.getKey()))
            .categories(new ArrayList<>())
            .categoryOrderHints(CategoryOrderHints.of(new HashMap<>()))
            .key("productKey5")
            .slug(LocalizedString.of(Locale.ENGLISH, "slug5"))
            .masterVariant(ProductVariantDraftBuilder.of().key("mv5").sku("sku5").build())
            .build();

        final ProductDraft key6Draft = createProductDraftBuilder(PRODUCT_KEY_2_RESOURCE_PATH,
            ProductType.referenceOfId(productType.getKey()))
            .taxCategory(TaxCategory.referenceOfId(targetTaxCategory.getKey()))
            .state(State.referenceOfId(targetProductState.getKey()))
            .categories(new ArrayList<>())
            .categoryOrderHints(CategoryOrderHints.of(new HashMap<>()))
            .key("productKey6")
            .slug(LocalizedString.of(Locale.ENGLISH, "slug6"))
            .masterVariant(ProductVariantDraftBuilder.of().key("mv6").sku("sku6").build())
            .build();

        final List<ProductDraft> batch = new ArrayList<>();
        batch.add(productDraft);
        batch.add(key3Draft);
        batch.add(key4Draft);
        batch.add(key5Draft);
        batch.add(key6Draft);

        final ProductSync productSync = new ProductSync(syncOptions);
        final ProductSyncStatistics syncStatistics = executeBlocking(productSync.sync(batch));

        assertThat(syncStatistics).hasValues(5, 4, 1, 0);
        assertThat(errorCallBackExceptions).isEmpty();
        assertThat(errorCallBackMessages).isEmpty();
        assertThat(warningCallBackMessages).isEmpty();
    }

    @Test
    void sync_withSameSlugInSingleBatch_ShouldNotSyncIt() {
        // Prepare batches from external source
        final ProductDraft productDraft = createProductDraft(PRODUCT_KEY_1_CHANGED_RESOURCE_PATH,
            ProductType.referenceOfId(productType.getKey()), TaxCategory.referenceOfId(targetTaxCategory.getKey()),
            State.referenceOfId(targetProductState.getKey()), categoryResourceIdentifiersWithKeys,
            categoryOrderHintsWithKeys);

        final ProductDraft key3Draft = createProductDraftBuilder(PRODUCT_KEY_2_RESOURCE_PATH,
            ProductType.referenceOfId(productType.getKey()))
            .taxCategory(null)
            .state(null)
            .categories(new ArrayList<>())
            .categoryOrderHints(CategoryOrderHints.of(new HashMap<>()))
            .key("productKey3")
            .masterVariant(ProductVariantDraftBuilder.of().key("k3").sku("s3").build())
            .build();

        final ProductDraft key4Draft = createProductDraftBuilder(PRODUCT_KEY_2_RESOURCE_PATH,
            ProductType.referenceOfId(productType.getKey()))
            .taxCategory(null)
            .state(null)
            .categories(new ArrayList<>())
            .categoryOrderHints(CategoryOrderHints.of(new HashMap<>()))
            .key("productKey4")
            .masterVariant(ProductVariantDraftBuilder.of().key("k4").sku("s4").build())
            .build();

        final ProductDraft key5Draft = createProductDraftBuilder(PRODUCT_KEY_2_RESOURCE_PATH,
            ProductType.referenceOfId(productType.getKey()))
            .taxCategory(null)
            .state(null)
            .categories(new ArrayList<>())
            .categoryOrderHints(CategoryOrderHints.of(new HashMap<>()))
            .key("productKey5")
            .masterVariant(ProductVariantDraftBuilder.of().key("k5").sku("s5").build())
            .build();

        final ProductDraft key6Draft = createProductDraftBuilder(PRODUCT_KEY_2_RESOURCE_PATH,
            ProductType.referenceOfId(productType.getKey()))
            .taxCategory(null)
            .state(null)
            .categories(new ArrayList<>())
            .categoryOrderHints(CategoryOrderHints.of(new HashMap<>()))
            .key("productKey6")
            .masterVariant(ProductVariantDraftBuilder.of().key("k6").sku("s6").build())
            .build();

        final List<ProductDraft> batch = new ArrayList<>();
        batch.add(productDraft);
        batch.add(key3Draft);
        batch.add(key4Draft);
        batch.add(key5Draft);
        batch.add(key6Draft);

        final ProductSync productSync = new ProductSync(syncOptions);
        final ProductSyncStatistics syncStatistics = executeBlocking(productSync.sync(batch));

        assertThat(syncStatistics).hasValues(5, 1, 1, 3);

        final String duplicatedSlug = key3Draft.getSlug().get(Locale.ENGLISH);
        assertThat(errorCallBackExceptions).hasSize(3);
        assertThat(errorCallBackExceptions).allSatisfy(exception -> {
            assertThat(exception).isExactlyInstanceOf(ErrorResponseException.class);
            final ErrorResponseException errorResponse = ((ErrorResponseException)exception);

            final List<DuplicateFieldError> fieldErrors = errorResponse
                .getErrors()
                .stream()
                .map(sphereError -> {
                    assertThat(sphereError.getCode()).isEqualTo(DuplicateFieldError.CODE);
                    return sphereError.as(DuplicateFieldError.class);
                })
                .collect(toList());
            assertThat(fieldErrors).hasSize(1);
            assertThat(fieldErrors).allSatisfy(error -> {
                assertThat(error.getField()).isEqualTo("slug.en");
                assertThat(error.getDuplicateValue()).isEqualTo(duplicatedSlug);
            });
        });

        assertThat(errorCallBackMessages)
            .hasSize(3)
            .allSatisfy(errorMessage -> {
                assertThat(errorMessage).contains("\"code\" : \"DuplicateField\"");
                assertThat(errorMessage).contains("\"field\" : \"slug.en\"");
                assertThat(errorMessage).contains(format("\"duplicateValue\" : \"%s\"", duplicatedSlug));
            });
        assertThat(warningCallBackMessages).isEmpty();
    }

    @Test
    void sync_withADraftsWithBlankKeysInBatch_ShouldNotSyncItAndTriggerErrorCallBack() {
        // Prepare batches from external source
        final ProductDraft productDraft = createProductDraft(PRODUCT_KEY_1_CHANGED_RESOURCE_PATH,
            ProductType.reference(productType.getKey()), TaxCategory.referenceOfId(targetTaxCategory.getKey()),
            State.referenceOfId(targetProductState.getKey()), categoryResourceIdentifiersWithKeys,
            categoryOrderHintsWithKeys);

        // Draft with null key
        final ProductDraft key3Draft = createProductDraftBuilder(PRODUCT_KEY_2_RESOURCE_PATH,
            ProductType.reference(productType.getKey()))
            .taxCategory(null)
            .state(null)
            .categories(new ArrayList<>())
            .categoryOrderHints(CategoryOrderHints.of(new HashMap<>()))
            .key(null)
            .masterVariant(ProductVariantDraftBuilder.of().build())
            .productType(ProductType.referenceOfId(productType.getKey()))
            .build();

        // Draft with empty key
        final ProductDraft key4Draft = createProductDraftBuilder(PRODUCT_KEY_2_RESOURCE_PATH,
            ProductType.reference(productType.getKey()))
            .taxCategory(null)
            .state(null)
            .categories(new ArrayList<>())
            .categoryOrderHints(CategoryOrderHints.of(new HashMap<>()))
            .key("")
            .masterVariant(ProductVariantDraftBuilder.of().build())
            .productType(ProductType.referenceOfId(productType.getKey()))
            .build();

        final List<ProductDraft> batch = new ArrayList<>();
        batch.add(productDraft);
        batch.add(key3Draft);
        batch.add(key4Draft);

        final ProductSync productSync = new ProductSync(syncOptions);
        final ProductSyncStatistics syncStatistics = executeBlocking(productSync.sync(batch));

        assertThat(syncStatistics).hasValues(3, 0, 1, 2);
        assertThat(errorCallBackExceptions).hasSize(2);
        assertThat(errorCallBackMessages).hasSize(2);
        assertThat(errorCallBackMessages.get(0))
            .containsIgnoringCase(format("ProductDraft with name: %s doesn't have a key.", key3Draft.getName()));
        assertThat(errorCallBackMessages.get(1))
            .containsIgnoringCase(format("ProductDraft with name: %s doesn't have a key.", key4Draft.getName()));
        assertThat(warningCallBackMessages).isEmpty();
    }

    @Test
    void sync_withANullDraftInBatch_ShouldNotSyncItAndTriggerErrorCallBack() {
        // Prepare batches from external source
        final ProductDraft productDraft = createProductDraft(PRODUCT_KEY_1_CHANGED_RESOURCE_PATH,
            ProductType.reference(productType.getKey()), null, null,
            categoryResourceIdentifiersWithKeys, categoryOrderHintsWithKeys);

        final List<ProductDraft> batch = new ArrayList<>();
        batch.add(productDraft);
        batch.add(null);

        final ProductSync productSync = new ProductSync(syncOptions);
        final ProductSyncStatistics syncStatistics = executeBlocking(productSync.sync(batch));

        assertThat(syncStatistics).hasValues(2, 0, 1, 1);
        assertThat(errorCallBackExceptions).hasSize(1);
        assertThat(errorCallBackMessages).hasSize(1);
        assertThat(errorCallBackMessages.get(0)).isEqualToIgnoringCase("ProductDraft is null.");
        assertThat(warningCallBackMessages).isEmpty();
    }

    @Test
    void sync_withSameDraftsWithChangesInBatch_ShouldRetryUpdateBecauseOfConcurrentModificationExceptions() {
        // Prepare batches from external source
        final ProductDraft productDraft = createProductDraft(PRODUCT_KEY_1_CHANGED_RESOURCE_PATH,
            ProductType.reference(productType.getKey()), null, null,
            categoryResourceIdentifiersWithKeys, categoryOrderHintsWithKeys);

        // Draft with same key
        final ProductDraft draftWithSameKey = createProductDraftBuilder(PRODUCT_KEY_2_RESOURCE_PATH,
            ProductType.reference(productType.getKey()))
            .taxCategory(null)
            .state(null)
            .categories(new ArrayList<>())
            .categoryOrderHints(CategoryOrderHints.of(new HashMap<>()))
            .key(productDraft.getKey())
            .masterVariant(ProductVariantDraftBuilder.of(product.getMasterData().getStaged().getMasterVariant())
                                                     .build())
            .build();

        final List<ProductDraft> batch = new ArrayList<>();
        batch.add(productDraft);
        batch.add(draftWithSameKey);

        final ProductSync productSync = new ProductSync(syncOptions);
        final ProductSyncStatistics syncStatistics = executeBlocking(productSync.sync(batch));

        assertThat(syncStatistics).hasValues(2, 0, 2, 0);
        assertThat(errorCallBackExceptions).isEmpty();
        assertThat(errorCallBackMessages).isEmpty();
        assertThat(warningCallBackMessages).isEmpty();
    }

    @Test
    void sync_withProductBundle_shouldCreateProductReferencingExistingProduct() {
        final ProductDraft productDraft = createProductDraftBuilder(PRODUCT_KEY_2_RESOURCE_PATH,
            ProductType.referenceOfId(productType.getKey()))
            .taxCategory(null)
            .state(null)
            .build();

        // Creating the attribute draft with the product reference
        final AttributeDraft productReferenceAttribute =
            AttributeDraft.of("product-reference", Reference.of(Product.referenceTypeId(), product.getKey()));

        // Creating the product variant draft with the product reference attribute
        final ProductVariantDraft draftMasterVariant = productDraft.getMasterVariant();
        assertThat(draftMasterVariant).isNotNull();
        final List<AttributeDraft> attributes = draftMasterVariant.getAttributes();
        attributes.add(productReferenceAttribute);
        final ProductVariantDraft masterVariant = ProductVariantDraftBuilder.of(draftMasterVariant)
                                                                            .attributes(attributes)
                                                                            .build();

        final ProductDraft productDraftWithProductReference = ProductDraftBuilder.of(productDraft)
                                                                                 .masterVariant(masterVariant)
                                                                                 .build();


        final ProductSync productSync = new ProductSync(syncOptions);
        final ProductSyncStatistics syncStatistics =
            executeBlocking(productSync.sync(singletonList(productDraftWithProductReference)));

        assertThat(syncStatistics).hasValues(1, 1, 0, 0);
        assertThat(errorCallBackExceptions).isEmpty();
        assertThat(errorCallBackMessages).isEmpty();
        assertThat(warningCallBackMessages).isEmpty();
    }

    @Test
    void sync_withProductContainingAttributeChanges_shouldSyncProductCorrectly() {
        // preparation
        final List<UpdateAction<Product>> updateActions = new ArrayList<>();
        final Consumer<String> warningCallBack = warningMessage -> warningCallBackMessages.add(warningMessage);


        final ProductSyncOptions customOptions = ProductSyncOptionsBuilder.of(CTP_TARGET_CLIENT)
                                                                          .errorCallback(this::collectErrors)
                                                                          .warningCallback(warningCallBack)
                                                                          .beforeUpdateCallback(
                                                                              (actions, draft, old) -> {
                                                                                  updateActions.addAll(actions);
                                                                                  return actions;
                                                                              })
                                                                          .build();

        final ProductDraft productDraft = createProductDraftBuilder(PRODUCT_KEY_1_RESOURCE_PATH,
            ProductType.referenceOfId(productType.getKey()))
            .categories(emptyList())
            .taxCategory(null)
            .state(null)
            .build();

        // Creating the attribute draft with the changes
        final AttributeDraft priceInfoAttrDraft =
            AttributeDraft.of("priceInfo", JsonNodeFactory.instance.textNode("100/kg"));
        final AttributeDraft angebotAttrDraft =
            AttributeDraft.of("angebot", JsonNodeFactory.instance.textNode("big discount"));
        final AttributeDraft unknownAttrDraft =
            AttributeDraft.of("unknown", JsonNodeFactory.instance.textNode("unknown"));

        // Creating the product variant draft with the product reference attribute
        final List<AttributeDraft> attributes = asList(priceInfoAttrDraft, angebotAttrDraft, unknownAttrDraft);

        final ProductVariantDraft masterVariant = ProductVariantDraftBuilder.of(productDraft.getMasterVariant())
                                                                            .attributes(attributes)
                                                                            .build();

        final ProductDraft productDraftWithChangedAttributes = ProductDraftBuilder.of(productDraft)
                                                                                  .masterVariant(masterVariant)
                                                                                  .build();


        // test
        final ProductSync productSync = new ProductSync(customOptions);
        final ProductSyncStatistics syncStatistics =
            executeBlocking(productSync.sync(singletonList(productDraftWithChangedAttributes)));

        // assertion
        assertThat(syncStatistics).hasValues(1, 0, 1, 0);

        final String causeErrorMessage = format(ATTRIBUTE_NOT_IN_ATTRIBUTE_METADATA, unknownAttrDraft.getName());
        final String expectedErrorMessage = format(FAILED_TO_BUILD_ATTRIBUTE_UPDATE_ACTION, unknownAttrDraft.getName(),
            productDraft.getMasterVariant().getKey(), productDraft.getKey(), causeErrorMessage);

        assertThat(errorCallBackExceptions).hasSize(1);
        assertThat(errorCallBackExceptions.get(0).getMessage()).isEqualTo(expectedErrorMessage);
        assertThat(errorCallBackExceptions.get(0).getCause().getMessage()).isEqualTo(causeErrorMessage);
        assertThat(errorCallBackMessages).containsExactly(expectedErrorMessage);
        assertThat(warningCallBackMessages).isEmpty();

        assertThat(updateActions)
            .filteredOn(updateAction -> ! (updateAction instanceof SetTaxCategory))
            .filteredOn(updateAction -> ! (updateAction instanceof RemoveFromCategory))
            .containsExactlyInAnyOrder(
                SetAttributeInAllVariants.of(priceInfoAttrDraft, true),
                SetAttribute.of(1, angebotAttrDraft, true),
                SetAttributeInAllVariants.ofUnsetAttribute("size", true),
                SetAttributeInAllVariants.ofUnsetAttribute("rinderrasse", true),
                SetAttributeInAllVariants.ofUnsetAttribute("herkunft", true),
                SetAttributeInAllVariants.ofUnsetAttribute("teilstueck", true),
                SetAttributeInAllVariants.ofUnsetAttribute("fuetterung", true),
                SetAttributeInAllVariants.ofUnsetAttribute("reifung", true),
                SetAttributeInAllVariants.ofUnsetAttribute("haltbarkeit", true),
                SetAttributeInAllVariants.ofUnsetAttribute("verpackung", true),
                SetAttributeInAllVariants.ofUnsetAttribute("anlieferung", true),
                SetAttributeInAllVariants.ofUnsetAttribute("zubereitung", true),
                SetAttribute.ofUnsetAttribute(1, "localisedText", true)
            );
    }

    private void collectErrors(final String errorMessage, final Throwable exception) {
        errorCallBackMessages.add(errorMessage);
        errorCallBackExceptions.add(exception);
    }
}
