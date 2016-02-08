package nanomsg;

import com.sun.jna.Memory;
import java.util.HashMap;
import java.util.Map;

import nanomsg.NativeLibrary.NNPollEvent;
import nanomsg.exceptions.IOException;
import nanomsg.Socket;
import static nanomsg.Nanomsg.*;


public class Poller {
  public static final int POLLIN = nn_symbols.get("NN_POLLIN");
  public static final int POLLOUT = nn_symbols.get("NN_POLLOUT");

  private static final int SIZE_DEFAULT = 32;
  private static final int SIZE_INCREMENT = 16;
  private static final int EVENT_SIZE = (new NNPollEvent()).size();

  private final Map<Integer,Integer> offsetMap = new HashMap<Integer,Integer>(SIZE_DEFAULT);
  private Memory items;
  private int next;
  private int timeout;

  protected Poller(int size, int timeout) {
    this.items = new Memory(size * EVENT_SIZE);
    this.timeout = timeout;
    this.next = 0;
  }

  public Poller(int size) {
    this(size, -1);
  }

  public Poller() {
    this(SIZE_DEFAULT);
  }

  public void register(Socket socket) {
    this.register(socket, POLLIN | POLLOUT);
  }

  public void register(Socket socket, int flags) {
    int pos = this.next;
    this.next += EVENT_SIZE;

    if (pos > this.items.size()) {
      final long newSize = this.items.size() + (EVENT_SIZE * SIZE_INCREMENT);
      final Memory newItems = new Memory(newSize);

      for(int i=0; i<this.items.size(); i++) {
        newItems.setByte(i, this.items.getByte(i));
      }
    }

    final int socketFd = socket.getNativeSocket();
    final NNPollEvent.ByReference event = new NNPollEvent.ByReference();
    event.reuse(this.items, pos);
    event.fd = socketFd;
    event.events = (short)flags;

    this.offsetMap.put(socketFd, pos);
  }

  public void unregister(Socket socket) {
    final NNPollEvent.ByReference event = new NNPollEvent.ByReference();
    final int fd = socket.getNativeSocket();

    this.offsetMap.remove(fd);

    for (int i=0; i<this.items.size(); i = i+EVENT_SIZE) {
      event.reuse(this.items, i);

      if (event.fd == fd) {
        this.next -= EVENT_SIZE;

        if (i != this.next) {
          event.reuse(this.items, this.next);
          final int socketFd = event.fd;
          final short socketEvents = event.events;
          final short socketRevents = event.revents;

          event.reuse(this.items, i);
          event.fd = socketFd;
          event.events = socketEvents;
          event.revents = socketRevents;

          this.offsetMap.put(socketFd, i);
        }
        break;
      }
    }
  }

  public int poll() {
    return this.poll(this.timeout);
  }

  public int size() {
    return this.offsetMap.size();
  }

  public int poll(int timeout) {
    final int maxEvents = this.offsetMap.size();
    final int rc = NativeLibrary.nn_poll(this.items, maxEvents, timeout);

    if (rc < 0) {
      final int errno = getErrorNumber();
      final String msg = getError();
      throw new IOException(msg, errno);
    }

    return rc;
  }
}