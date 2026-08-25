export function createAudioSendQueue({ maxActive = 2, send, onError = () => {} } = {}) {
  const pending = [];
  let active = 0;
  let pumping = false;

  async function pump() {
    if (pumping) return;
    pumping = true;
    try {
      while (active < maxActive && pending.length) {
        const entry = pending.shift();
        active += 1;
        (async () => {
          try {
            await send(entry.item);
            active -= 1;
            entry.resolve();
          } catch (error) {
            active -= 1;
            onError(error, entry.item);
            entry.reject(error);
          }
          void pump();
        })();
      }
    } finally {
      pumping = false;
    }
  }

  return {
    enqueue(item) {
      return new Promise((resolve, reject) => {
        pending.push({ item, resolve, reject });
        void pump();
      });
    },
    clear() {
      const error = new Error("audio send queue cleared");
      error.code = "cleared";
      while (pending.length) pending.shift().reject(error);
    },
    snapshot() {
      return { queued: pending.length, active };
    },
  };
}
