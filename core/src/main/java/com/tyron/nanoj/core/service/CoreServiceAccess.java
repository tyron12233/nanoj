package com.tyron.nanoj.core.service;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.service.ServiceAccess;

import java.util.List;

public final class CoreServiceAccess implements ServiceAccess {

    @Override
    public <T> T getApplicationService(Class<T> serviceClass) {
        return ApplicationServiceManager.getService(serviceClass);
    }

    @Override
    public <E> List<E> getApplicationExtensions(Class<E> extensionPoint) {
        return ApplicationServiceManager.getExtensions(extensionPoint);
    }

    @Override
    public <T> T getProjectService(Project project, Class<T> serviceClass) {
        return ProjectServiceManager.getService(project, serviceClass);
    }

    @Override
    public <E> List<E> getProjectExtensions(Project project, Class<E> extensionPoint) {
        return ProjectServiceManager.getExtensions(project, extensionPoint);
    }
}
