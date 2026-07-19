package kim.mingyo.browserssh.web;

import kim.mingyo.browserssh.config.FileTransferProperties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
class FileTransferCapacity {
    private final FileTransferProperties properties;
    private final AtomicInteger connections = new AtomicInteger();
    private final Semaphore operations;

    FileTransferCapacity(FileTransferProperties properties) {
        this.properties = properties;
        this.operations = new Semaphore(properties.maxConcurrentOperations(), true);
    }

    boolean tryOpenConnection() {
        while (true) {
            int current = connections.get();
            if (current >= properties.maxConcurrentConnections()) {
                return false;
            }
            if (connections.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    void closeConnection() {
        connections.updateAndGet(current -> Math.max(0, current - 1));
    }

    boolean tryStartOperation() {
        return operations.tryAcquire();
    }

    void finishOperation() {
        operations.release();
    }
}
