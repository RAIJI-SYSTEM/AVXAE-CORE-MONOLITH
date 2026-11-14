package com.raiji.it;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.raiji.users.driververification.CnhImageStorage;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Armazenamento da foto da CNH em memória para os testes (sem S3/RustFS). */
public class InMemoryCnhImageStorage implements CnhImageStorage {

    public static final Map<String, byte[]> FILES = new ConcurrentHashMap<>();

    @Override
    public void put(String key, byte[] content, String contentType) {
        FILES.put(key, content);
    }

    @Override
    public byte[] get(String key) {
        byte[] content = FILES.get(key);
        if (content == null) throw new IllegalStateException("arquivo não encontrado: " + key);
        return content;
    }

    @Override
    public void delete(String key) {
        FILES.remove(key);
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        CnhImageStorage inMemoryCnhImageStorage() {
            return new InMemoryCnhImageStorage();
        }
    }
}
