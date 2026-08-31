package org.lytharalab.gfbs.auralis.api.source;

import java.util.Set;

public interface AudioDataSourceRegistry {
    void register(String typeId, AudioDataSourceFactory factory);
    boolean unregister(String typeId);
    boolean contains(String typeId);
    Set<String> types();
    AudioDataSource create(String typeId, AudioSourceRequest request) throws Exception;
}
