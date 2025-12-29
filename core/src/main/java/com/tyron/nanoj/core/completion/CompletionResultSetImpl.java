package com.tyron.nanoj.core.completion;

import com.tyron.nanoj.api.completion.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class CompletionResultSetImpl implements CompletionResultSet {

    private final List<LookupElement> resultList = Collections.synchronizedList(new ArrayList<>());
    private final PrefixMatcher matcher;
    private final CompletionProvider provider;
    private final AtomicBoolean isStopped = new AtomicBoolean(false);

    public CompletionResultSetImpl(PrefixMatcher matcher, CompletionProvider provider) {
        this.matcher = matcher;
        this.provider = provider;
    }

    @Override
    public void addElement(@NotNull LookupElement element) {
        if (isStopped.get()) return;

        if (!matcher.prefixMatches(element.getLookupString())) {
            return;
        }

        resultList.add(element);
    }

    @Override
    public void addAllElements(@NotNull Iterable<? extends LookupElement> elements) {
        for (LookupElement element : elements) {
            addElement(element);
        }
    }

    @Override
    public @NotNull CompletionResultSet withPrefixMatcher(@NotNull PrefixMatcher matcher) {
        return new CompletionResultSetImpl(matcher, provider);
    }

    @Override
    public @NotNull CompletionResultSet withPrefixMatcher(@NotNull String prefix) {
        return new CompletionResultSetImpl(new PrefixMatcher.Plain(prefix), provider);
    }

    @Override
    public boolean isStopped() {
        return isStopped.get() || Thread.currentThread().isInterrupted();
    }

    @Override
    public void stopHere() {
        isStopped.set(true);
    }

    @Override
    public @NotNull PrefixMatcher getPrefixMatcher() {
        return matcher;
    }
    
    public List<LookupElement> getResultList() {
        return resultList;
    }
}