package org.lytharalab.gfbs.auralis.api.source;

@FunctionalInterface
public interface AudioDataSourceFactory {
    AudioDataSource create(AudioSourceRequest request) throws Exception;
}
