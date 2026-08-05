interface CartItemsSnapshot<T> {
  ownerKey: string;
  value: T;
  storedAt: number;
}

interface CartItemsFlight<T> {
  ownerKey: string;
  promise: Promise<T>;
}

export class CartItemsCache<T> {
  private snapshot: CartItemsSnapshot<T> | null = null;
  private flight: CartItemsFlight<T> | null = null;
  private generation = 0;

  constructor(
    private readonly ttlMs: number,
    private readonly now: () => number = Date.now
  ) {}

  get(
    ownerKey: string,
    load: () => Promise<T>,
    preferCache: boolean
  ): Promise<T> {
    if (
      preferCache &&
      this.snapshot?.ownerKey === ownerKey &&
      this.now() - this.snapshot.storedAt < this.ttlMs
    ) {
      return Promise.resolve(this.snapshot.value);
    }

    if (this.flight?.ownerKey === ownerKey) {
      return this.flight.promise;
    }

    const generation = this.generation;
    const promise = load();
    const flight: CartItemsFlight<T> = { ownerKey, promise };
    this.flight = flight;
    void promise.then(
      (value) => {
        if (this.generation === generation && this.flight === flight) {
          this.snapshot = {
            ownerKey,
            value,
            storedAt: this.now()
          };
        }
        if (this.flight === flight) {
          this.flight = null;
        }
      },
      () => {
        if (this.flight === flight) {
          this.flight = null;
        }
      }
    );
    return promise;
  }

  invalidate(): void {
    this.generation += 1;
    this.snapshot = null;
    this.flight = null;
  }
}
