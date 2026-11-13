package ai.awe.registry.source;

import ai.awe.registry.source.postgres.PostgresMetadataAdapter;
import ai.awe.registry.source.postgres.PostgresWarehouse;
import ai.awe.registry.support.JsonDocuments;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class SourceAdapters {
    @Bean
    public SourceAdapter postgresMetadataSource(PostgresWarehouse warehouse) {
        return new PostgresMetadataAdapter(warehouse);
    }

    @Bean
    public SourceAdapter hiveMetadataSource(Environment env, JsonDocuments json) {
        return new HttpMetadataAdapter(
                "hive",
                env.getProperty("AWE_HIVE_METADATA_URL", ""),
                env.getProperty("AWE_HIVE_METADATA_TOKEN", ""),
                json);
    }

    @Bean
    public SourceAdapter biMetadataSource(Environment env, JsonDocuments json) {
        return new HttpMetadataAdapter(
                "bi",
                env.getProperty("AWE_BI_METADATA_URL", ""),
                env.getProperty("AWE_BI_METADATA_TOKEN", ""),
                json);
    }
}
