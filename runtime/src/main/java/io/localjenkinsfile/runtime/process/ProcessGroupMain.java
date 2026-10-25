package io.localjenkinsfile.runtime.process;

/** Small JVM child establishes a Unix group before creating the user shell. */
public final class ProcessGroupMain {
    private ProcessGroupMain() {}
    public static void main(String[] args) throws Exception {
        UnixProcessGroup.establish();
        System.out.println("LOCAL_JENKINSFILE_GROUP_READY");
        System.out.flush();
        Process child = new ProcessBuilder(args).inheritIO().start();
        System.exit(child.waitFor());
    }
}
