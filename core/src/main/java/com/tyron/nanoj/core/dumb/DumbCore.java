package com.tyron.nanoj.core.dumb;

import com.tyron.nanoj.api.dumb.DumbService;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.core.service.ProjectServiceManager;

/**
 * Convenience registration for dumb mode infrastructure.
 */
public final class DumbCore {

    private DumbCore() {
    }

    public static void register(Project project) {
        ProjectServiceManager.registerBindingIfAbsent(project, DumbService.class, DumbServiceImpl.class);
    }
}
