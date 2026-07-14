package org.muybaby.shopserver.storage.provider;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import jakarta.annotation.PreDestroy;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.config.ResolvedStorageConfig;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.file.Path;

public class RoutingStorageProvider implements StorageProvider {

    private final StorageRuntimeConfigService configService;
    private volatile LocalHolder localHolder;
    private volatile CosHolder cosHolder;

    public RoutingStorageProvider(StorageRuntimeConfigService configService) {
        this.configService = configService;
    }

    @Override
    public StoredObject put(String objectKey, String contentType, InputStream inputStream, long sizeBytes) {
        ResolvedStorageConfig config = configService.effective();
        return put(config.provider(), objectKey, contentType, inputStream, sizeBytes);
    }

    @Override
    public StoredObject put(
            StorageProviderKind provider,
            String objectKey,
            String contentType,
            InputStream inputStream,
            long sizeBytes
    ) {
        ResolvedStorageConfig config = configService.effective();
        if (provider == StorageProviderKind.LOCAL) {
            return local(config).put(objectKey, contentType, inputStream, sizeBytes);
        }

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(sizeBytes);
        metadata.setContentType(contentType);
        PutObjectRequest request = new PutObjectRequest(config.cosBucket(), objectKey, inputStream, metadata);
        request.setCannedAcl(objectKey.startsWith("public/")
                ? CannedAccessControlList.PublicRead
                : CannedAccessControlList.Private);
        cos(config).putObject(request);
        return new StoredObject(objectKey, contentType, InputStream.nullInputStream(), sizeBytes);
    }

    @Override
    public StoredObject open(String objectKey) {
        ResolvedStorageConfig config = configService.effective();
        return open(config.provider(), objectKey);
    }

    @Override
    public StoredObject open(StorageProviderKind provider, String objectKey) {
        ResolvedStorageConfig config = configService.effective();
        if (provider == StorageProviderKind.LOCAL) {
            return local(config).open(objectKey);
        }
        COSObject object = cos(config).getObject(config.cosBucket(), objectKey);
        ObjectMetadata metadata = object.getObjectMetadata();
        return new StoredObject(
                objectKey,
                metadata.getContentType(),
                object.getObjectContent(),
                metadata.getContentLength()
        );
    }

    @Override
    public void delete(String objectKey) {
        ResolvedStorageConfig config = configService.effective();
        delete(config.provider(), objectKey);
    }

    @Override
    public void delete(StorageProviderKind provider, String objectKey) {
        ResolvedStorageConfig config = configService.effective();
        if (provider == StorageProviderKind.LOCAL) {
            local(config).delete(objectKey);
            return;
        }
        cos(config).deleteObject(config.cosBucket(), objectKey);
    }

    @PreDestroy
    void shutdown() {
        CosHolder holder = cosHolder;
        if (holder != null) {
            holder.client().shutdown();
        }
    }

    private LocalStorageProvider local(ResolvedStorageConfig config) {
        if (!StringUtils.hasText(config.localRoot())) {
            throw new IllegalStateException("Local storage root is not configured");
        }
        Path root = Path.of(config.localRoot()).toAbsolutePath().normalize();
        LocalHolder holder = localHolder;
        if (holder != null && holder.root().equals(root)) {
            return holder.provider();
        }
        synchronized (this) {
            holder = localHolder;
            if (holder == null || !holder.root().equals(root)) {
                holder = new LocalHolder(root, new LocalStorageProvider(root));
                localHolder = holder;
            }
            return holder.provider();
        }
    }

    private COSClient cos(ResolvedStorageConfig config) {
        requireCosConfig(config);
        CosKey key = new CosKey(config.cosRegion(), config.cosSecretId(), config.cosSecretKey());
        CosHolder holder = cosHolder;
        if (holder != null && holder.key().equals(key)) {
            return holder.client();
        }
        synchronized (this) {
            holder = cosHolder;
            if (holder == null || !holder.key().equals(key)) {
                COSCredentials credentials = new BasicCOSCredentials(config.cosSecretId(), config.cosSecretKey());
                COSClient client = new COSClient(credentials, new ClientConfig(new Region(config.cosRegion())));
                CosHolder previous = cosHolder;
                cosHolder = new CosHolder(key, client);
                if (previous != null) {
                    previous.client().shutdown();
                }
            }
            return cosHolder.client();
        }
    }

    private void requireCosConfig(ResolvedStorageConfig config) {
        if (!StringUtils.hasText(config.cosRegion())
                || !StringUtils.hasText(config.cosBucket())
                || !StringUtils.hasText(config.cosSecretId())
                || !StringUtils.hasText(config.cosSecretKey())) {
            throw new IllegalStateException("Tencent COS storage is not fully configured");
        }
    }

    private record LocalHolder(Path root, LocalStorageProvider provider) {
    }

    private record CosKey(String region, String secretId, String secretKey) {
    }

    private record CosHolder(CosKey key, COSClient client) {
    }
}
