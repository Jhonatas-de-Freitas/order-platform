create table outbox (
    id           bigint generated always as identity primary key,
    event_id     uuid not null unique,
    topic        text not null,
    message_key  text not null,
    event_type   text not null,
    payload      text not null,
    created_at   timestamptz not null,
    published_at timestamptz
);

-- The publisher only ever looks at what is still pending.
create index outbox_pending_idx on outbox (id) where published_at is null;
