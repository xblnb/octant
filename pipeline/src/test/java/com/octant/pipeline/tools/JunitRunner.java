package com.octant.pipeline.tools;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

public final class JunitRunner {

    private JunitRunner() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("用法：JunitRunner <全限定测试类名> [<类名>…]");
            System.exit(2);
        }
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(java.util.Arrays.stream(args)
                        .map(DiscoverySelectors::selectClass)
                        .toArray(org.junit.platform.engine.DiscoverySelector[]::new))
                .build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(request, listener);
        TestExecutionSummary summary = listener.getSummary();
        PrintWriter out = new PrintWriter(System.out);
        summary.printTo(out);
        summary.printFailuresTo(out, 20);
        out.flush();
        System.out.println("TESTS=" + summary.getTestsStartedCount()
                + " SUCCEEDED=" + summary.getTestsSucceededCount()
                + " FAILED=" + summary.getTestsFailedCount());
        System.exit(summary.getTestsFailedCount() == 0 ? 0 : 1);
    }
}
