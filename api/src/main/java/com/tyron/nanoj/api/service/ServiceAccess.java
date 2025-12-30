package com.tyron.nanoj.api.service;

import com.tyron.nanoj.api.project.Project;

import java.util.List;

/**
 * Bridge between API-level convenience methods (e.g. {@code project.getService(...)}) and the
 * concrete container implementation living in {@code :core}.
 */
public interface ServiceAccess {

    <T> T getApplicationService(Class<T> serviceClass);

    <E> List<E> getApplicationExtensions(Class<E> extensionPoint);

    <T> T getProjectService(Project project, Class<T> serviceClass);

    <E> List<E> getProjectExtensions(Project project, Class<E> extensionPoint);
}
