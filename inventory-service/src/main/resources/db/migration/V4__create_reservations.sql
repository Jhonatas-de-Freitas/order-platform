create table reservations (
    id          uuid primary key,
    -- One reservation per order, even if the same order arrives in two different events.
    order_id    uuid not null unique,
    status      text not null,
    created_at  timestamptz not null,
    released_at timestamptz
);

create table reservation_items (
    reservation_id uuid not null references reservations (id),
    seq            integer not null,
    product_id     uuid not null references products (id),
    product_name   text not null,
    quantity       integer not null check (quantity > 0),
    unit_price     numeric(12, 2) not null,
    primary key (reservation_id, seq)
);
