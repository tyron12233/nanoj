package com.tyron.nanoj.core.completion;

import com.tyron.nanoj.api.completion.AutoPopupController;
import com.tyron.nanoj.api.completion.CodeCompletionService;
import com.tyron.nanoj.api.completion.LookupElementWeigher;
import com.tyron.nanoj.api.concurrent.TaskScheduler;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.core.concurrent.TaskSchedulerImpl;
import com.tyron.nanoj.core.dumb.DumbCore;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.core.completion.weighers.ElementKindWeigher;
import com.tyron.nanoj.core.completion.weighers.ExactPrefixWeigher;

/**
 * Convenience registration for completion infrastructure.
 */
public final class CompletionCore {

    private CompletionCore() {
    }

    public static void register(Project project) {
        DumbCore.register(project);
        ProjectServiceManager.registerBindingIfAbsent(project, TaskScheduler.class, TaskSchedulerImpl.class);
        ProjectServiceManager.registerBinding(project, CodeCompletionService.class, CodeCompletionServiceImpl.class);
        ProjectServiceManager.registerBinding(project, AutoPopupController.class, AutoPopupControllerImpl.class);

        ProjectServiceManager.registerExtension(project, LookupElementWeigher.class, ElementKindWeigher.class);
        ProjectServiceManager.registerExtension(project, LookupElementWeigher.class, ExactPrefixWeigher.class);
    }
}
