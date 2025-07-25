/*
 * Copyright 2002-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.test.context.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ActiveProfilesResolver;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestContextTestUtils;
import org.springframework.test.context.support.AnnotationConfigContextLoader;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.cache.ContextCacheTestUtils.assertContextCacheStatistics;

/**
 * Integration tests for verifying proper behavior of the {@link ContextCache} in
 * conjunction with cache keys used in {@link TestContext}.
 *
 * @author Sam Brannen
 * @author Michail Nikolaev
 * @since 3.1
 * @see LruContextCacheTests
 * @see SpringExtensionContextCacheTests
 */
class ContextCacheTests {

	private final ContextCache contextCache = new DefaultContextCache();


	@BeforeEach
	void initialCacheState() {
		assertContextCacheStatistics(contextCache, "initial state", 0, 0, 0, 0);
		assertParentContextCount(0);
	}

	private void assertParentContextCount(int expected) {
		assertThat(contextCache.getParentContextCount()).as("parent context count").isEqualTo(expected);
	}

	private MergedContextConfiguration getMergedContextConfiguration(TestContext testContext) {
		return (MergedContextConfiguration) ReflectionTestUtils.getField(testContext, "mergedConfig");
	}

	private void loadCtxAndAssertStats(Class<?> testClass, int expectedSize, int expectedActiveContextsCount,
			int expectedHitCount, int expectedMissCount) {

		TestContext testContext = TestContextTestUtils.buildTestContext(testClass, contextCache);

		assertThat(testContext.getApplicationContext()).isNotNull();
		assertContextCacheStatistics(contextCache, testClass.getName(), expectedSize, expectedActiveContextsCount,
				expectedHitCount, expectedMissCount);
		testContext.markApplicationContextUnused();
	}


	@Test
	void verifyCacheKeyIsBasedOnContextLoader() {
		loadCtxAndAssertStats(AnnotationConfigContextLoaderTestCase.class, 1, 1, 0, 1);
		loadCtxAndAssertStats(AnnotationConfigContextLoaderTestCase.class, 1, 1, 1, 1);
		loadCtxAndAssertStats(CustomAnnotationConfigContextLoaderTestCase.class, 2, 1, 1, 2);
		loadCtxAndAssertStats(CustomAnnotationConfigContextLoaderTestCase.class, 2, 1, 2, 2);
		loadCtxAndAssertStats(AnnotationConfigContextLoaderTestCase.class, 2, 1, 3, 2);
		loadCtxAndAssertStats(CustomAnnotationConfigContextLoaderTestCase.class, 2, 1, 4, 2);
	}

	@Test
	void verifyCacheKeyIsBasedOnActiveProfiles() {
		int size = 0, hit = 0, miss = 0;
		loadCtxAndAssertStats(FooBarProfilesTestCase.class, ++size, 1, hit, ++miss);
		loadCtxAndAssertStats(FooBarProfilesTestCase.class, size, 1, ++hit, miss);
		// Profiles {foo, bar} should not hash to the same as {bar,foo}
		loadCtxAndAssertStats(BarFooProfilesTestCase.class, ++size, 1, hit, ++miss);
		loadCtxAndAssertStats(FooBarProfilesTestCase.class, size, 1, ++hit, miss);
		loadCtxAndAssertStats(FooBarProfilesTestCase.class, size, 1, ++hit, miss);
		loadCtxAndAssertStats(BarFooProfilesTestCase.class, size, 1, ++hit, miss);
		loadCtxAndAssertStats(FooBarActiveProfilesResolverTestCase.class, size, 1, ++hit, miss);
	}

	@Test
	void verifyCacheBehaviorForContextHierarchies() {
		int size = 0;
		int hits = 0;
		int misses = 0;

		// Level 1
		loadCtxAndAssertStats(ContextHierarchyLevel1TestCase.class, ++size, 1, hits, ++misses);
		loadCtxAndAssertStats(ContextHierarchyLevel1TestCase.class, size, 1, ++hits, misses);

		// Level 2
		loadCtxAndAssertStats(ContextHierarchyLevel2TestCase.class, ++size /* L2 */, 2, ++hits /* L1 */,
			++misses /* L2 */);
		loadCtxAndAssertStats(ContextHierarchyLevel2TestCase.class, size, 2, ++hits /* L2 */, misses);
		loadCtxAndAssertStats(ContextHierarchyLevel2TestCase.class, size, 2, ++hits /* L2 */, misses);

		// Level 3-A
		loadCtxAndAssertStats(ContextHierarchyLevel3a1TestCase.class, ++size /* L3A */, 3, ++hits /* L2 */,
			++misses /* L3A */);
		loadCtxAndAssertStats(ContextHierarchyLevel3a1TestCase.class, size, 3, ++hits /* L3A */, misses);

		// Level 3-B
		loadCtxAndAssertStats(ContextHierarchyLevel3bTestCase.class, ++size /* L3B */, 3, ++hits /* L2 */,
			++misses /* L3B */);
		loadCtxAndAssertStats(ContextHierarchyLevel3bTestCase.class, size, 3, ++hits /* L3B */, misses);
	}

	@Test
	void removeContextHierarchyCacheLevel1() {

		// Load Level 3-A
		TestContext testContext3a = TestContextTestUtils.buildTestContext(
			ContextHierarchyLevel3a1TestCase.class, contextCache);
		testContext3a.getApplicationContext();
		assertContextCacheStatistics(contextCache, "level 3, A", 3, 3, 0, 3);
		assertParentContextCount(2);
		testContext3a.markApplicationContextUnused();
		assertContextCacheStatistics(contextCache, "level 3, A", 3, 0, 0, 3);

		// Load Level 3-B
		TestContext testContext3b = TestContextTestUtils.buildTestContext(
			ContextHierarchyLevel3bTestCase.class, contextCache);
		testContext3b.getApplicationContext();
		assertContextCacheStatistics(contextCache, "level 3, A and B", 4, 3, 1, 4);
		assertParentContextCount(2);
		testContext3b.markApplicationContextUnused();
		assertContextCacheStatistics(contextCache, "level 3, A and B", 4, 0, 1, 4);

		// Remove Level 1
		// Should also remove Levels 2, 3-A, and 3-B, leaving nothing.
		contextCache.remove(getMergedContextConfiguration(testContext3a).getParent().getParent(),
			HierarchyMode.CURRENT_LEVEL);
		assertContextCacheStatistics(contextCache, "removed level 1", 0, 0, 1, 4);
		assertParentContextCount(0);
	}

	@Test
	void removeContextHierarchyCacheLevel1WithExhaustiveMode() {

		// Load Level 3-A
		TestContext testContext3a = TestContextTestUtils.buildTestContext(
			ContextHierarchyLevel3a1TestCase.class, contextCache);
		testContext3a.getApplicationContext();
		assertContextCacheStatistics(contextCache, "level 3, A", 3, 3, 0, 3);
		assertParentContextCount(2);
		testContext3a.markApplicationContextUnused();
		assertContextCacheStatistics(contextCache, "level 3, A", 3, 0, 0, 3);

		// Load Level 3-B
		TestContext testContext3b = TestContextTestUtils.buildTestContext(
			ContextHierarchyLevel3bTestCase.class, contextCache);
		testContext3b.getApplicationContext();
		assertContextCacheStatistics(contextCache, "level 3, A and B", 4, 3, 1, 4);
		assertParentContextCount(2);
		testContext3b.markApplicationContextUnused();
		assertContextCacheStatistics(contextCache, "level 3, A and B", 4, 0, 1, 4);

		// Remove Level 1
		// Should also remove Levels 2, 3-A, and 3-B, leaving nothing.
		contextCache.remove(getMergedContextConfiguration(testContext3a).getParent().getParent(),
			HierarchyMode.EXHAUSTIVE);
		assertContextCacheStatistics(contextCache, "removed level 1", 0, 0, 1, 4);
		assertParentContextCount(0);
	}

	@Test
	void removeContextHierarchyCacheLevel2() {

		// Load Level 3-A
		TestContext testContext3a = TestContextTestUtils.buildTestContext(
			ContextHierarchyLevel3a1TestCase.class, contextCache);
		testContext3a.getApplicationContext();
		assertContextCacheStatistics(contextCache, "level 3, A", 3, 3, 0, 3);
		assertParentContextCount(2);
		testContext3a.markApplicationContextUnused();
		assertContextCacheStatistics(contextCache, "level 3, A", 3, 0, 0, 3);

		// Load Level 3-B
		TestContext testContext3b = TestContextTestUtils.buildTestContext(
			ContextHierarchyLevel3bTestCase.class, contextCache);
		testContext3b.getApplicationContext();
		assertContextCacheStatistics(contextCache, "level 3, A and B", 4, 3, 1, 4);
		assertParentContextCount(2);
		testContext3b.markApplicationContextUnused();
		assertContextCacheStatistics(contextCache, "level 3, A and B", 4, 0, 1, 4);

		// Remove Level 2
		// Should also remove Levels 3-A and 3-B, leaving only Level 1 as a context in the
		// cache but also removing the Level 1 hierarchy since all children have been
		// removed.
		contextCache.remove(getMergedContextConfiguration(testContext3a).getParent(), HierarchyMode.CURRENT_LEVEL);
		assertContextCacheStatistics(contextCache, "removed level 2", 1, 0, 1, 4);
		assertParentContextCount(0);
	}

	@Test
	void removeContextHierarchyCacheLevel2WithExhaustiveMode() {

		// Load Level 3-A
		TestContext testContext3a = TestContextTestUtils.buildTestContext(
			ContextHierarchyLevel3a1TestCase.class, contextCache);
		testContext3a.getApplicationContext();
		assertContextCacheStatistics(contextCache, "level 3, A", 3, 3, 0, 3);
		assertParentContextCount(2);
		testContext3a.markApplicationContextUnused();
		assertContextCacheStatistics(contextCache, "level 3, A", 3, 0, 0, 3);

		// Load Level 3-B
		TestContext testContext3b = TestContextTestUtils.buildTestContext(
			ContextHierarchyLevel3bTestCase.class, contextCache);
		testContext3b.getApplicationContext();
		assertContextCacheStatistics(contextCache, "level 3, A and B", 4, 3, 1, 4);
		assertParentContextCount(2);
		testContext3b.markApplicationContextUnused();
		assertContextCacheStatistics(contextCache, "level 3, A and B", 4, 0, 1, 4);

		// Remove Level 2
		// Should wipe the cache
		contextCache.remove(getMergedContextConfiguration(testContext3a).getParent(), HierarchyMode.EXHAUSTIVE);
		assertContextCacheStatistics(contextCache, "removed level 2", 0, 0, 1, 4);
		assertParentContextCount(0);
	}

	@Test
	void removeContextHierarchyCacheLevel3Then2() {

		// Load Level 3-A
		TestContext testContext3a = TestContextTestUtils.buildTestContext(
			ContextHierarchyLevel3a1TestCase.class, contextCache);
		testContext3a.getApplicationContext();
		assertContextCacheStatistics(contextCache, "level 3, A", 3, 3, 0, 3);
		assertParentContextCount(2);
		testContext3a.markApplicationContextUnused();
		assertContextCacheStatistics(contextCache, "level 3, A", 3, 0, 0, 3);

		// Load Level 3-B
		TestContext testContext3b = TestContextTestUtils.buildTestContext(
			ContextHierarchyLevel3bTestCase.class, contextCache);
		testContext3b.getApplicationContext();
		assertContextCacheStatistics(contextCache, "level 3, A and B", 4, 3, 1, 4);
		assertParentContextCount(2);
		testContext3b.markApplicationContextUnused();
		assertContextCacheStatistics(contextCache, "level 3, A and B", 4, 0, 1, 4);

		// Remove Level 3-A
		contextCache.remove(getMergedContextConfiguration(testContext3a), HierarchyMode.CURRENT_LEVEL);
		assertContextCacheStatistics(contextCache, "removed level 3-A", 3, 0, 1, 4);
		assertParentContextCount(2);

		// Remove Level 2
		// Should also remove Level 3-B, leaving only Level 1.
		contextCache.remove(getMergedContextConfiguration(testContext3b).getParent(), HierarchyMode.CURRENT_LEVEL);
		assertContextCacheStatistics(contextCache, "removed level 2", 1, 0, 1, 4);
		assertParentContextCount(0);
	}

	@Test
	void removeContextHierarchyCacheLevel3Then2WithExhaustiveMode() {

		// Load Level 3-A
		TestContext testContext3a = TestContextTestUtils.buildTestContext(
			ContextHierarchyLevel3a1TestCase.class, contextCache);
		testContext3a.getApplicationContext();
		assertContextCacheStatistics(contextCache, "level 3, A", 3, 3, 0, 3);
		assertParentContextCount(2);
		testContext3a.markApplicationContextUnused();
		assertContextCacheStatistics(contextCache, "level 3, A", 3, 0, 0, 3);

		// Load Level 3-B
		TestContext testContext3b = TestContextTestUtils.buildTestContext(
			ContextHierarchyLevel3bTestCase.class, contextCache);
		testContext3b.getApplicationContext();
		assertContextCacheStatistics(contextCache, "level 3, A and B", 4, 3, 1, 4);
		assertParentContextCount(2);
		testContext3b.markApplicationContextUnused();
		assertContextCacheStatistics(contextCache, "level 3, A and B", 4, 0, 1, 4);

		// Remove Level 3-A
		// Should wipe the cache.
		contextCache.remove(getMergedContextConfiguration(testContext3a), HierarchyMode.EXHAUSTIVE);
		assertContextCacheStatistics(contextCache, "removed level 3-A", 0, 0, 1, 4);
		assertParentContextCount(0);

		// Remove Level 2
		// Should not actually do anything since the cache was cleared in the
		// previous step. So the stats should remain the same.
		contextCache.remove(getMergedContextConfiguration(testContext3b).getParent(), HierarchyMode.EXHAUSTIVE);
		assertContextCacheStatistics(contextCache, "removed level 2", 0, 0, 1, 4);
		assertParentContextCount(0);
	}


	@Configuration
	static class Config {
	}

	@ContextConfiguration(classes = Config.class, loader = AnnotationConfigContextLoader.class)
	private static class AnnotationConfigContextLoaderTestCase {
	}

	@ContextConfiguration(classes = Config.class, loader = CustomAnnotationConfigContextLoader.class)
	private static class CustomAnnotationConfigContextLoaderTestCase {
	}

	private static class CustomAnnotationConfigContextLoader extends AnnotationConfigContextLoader {
	}

	@ActiveProfiles({ "foo", "bar" })
	@ContextConfiguration(classes = Config.class, loader = AnnotationConfigContextLoader.class)
	private static class FooBarProfilesTestCase {
	}

	@ActiveProfiles({ "bar", "foo" })
	@ContextConfiguration(classes = Config.class, loader = AnnotationConfigContextLoader.class)
	private static class BarFooProfilesTestCase {
	}

	private static class FooBarActiveProfilesResolver implements ActiveProfilesResolver {

		@Override
		public String[] resolve(Class<?> testClass) {
			return new String[] { "foo", "bar" };
		}
	}

	@ActiveProfiles(resolver = FooBarActiveProfilesResolver.class)
	@ContextConfiguration(classes = Config.class, loader = AnnotationConfigContextLoader.class)
	private static class FooBarActiveProfilesResolverTestCase {
	}

}
