package org.infinispan.persistence.remote;

import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.util.concurrent.IsolationLevel;
import org.testng.annotations.Test;

/**
 * @author gustavonalle
 * @since 9.0
 */
@Test(testName = "persistence.remote.GetWithMetadataRepeatableRead", groups = "functional")
public class GetWithMetadataRepeatableRead extends GetWithMetadataTest {

   @Override
   protected ConfigurationBuilder getTargetCacheConfiguration(int sourcePort) {
      ConfigurationBuilder targetCacheConfiguration = super.getTargetCacheConfiguration(sourcePort);
      targetCacheConfiguration.locking().isolationLevel(IsolationLevel.REPEATABLE_READ);
      return targetCacheConfiguration;
   }
}
