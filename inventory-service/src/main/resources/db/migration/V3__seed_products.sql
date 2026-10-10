-- The demo catalog. Each product is there for a scenario: the headset has no stock
-- and the notebook costs more than the payment limit.
insert into products (id, name, description, price, available_quantity) values
    ('11111111-1111-1111-1111-111111111111', 'Teclado Mecânico', 'Teclado mecânico ABNT2 com switches táteis', 349.90, 50),
    ('22222222-2222-2222-2222-222222222222', 'Mouse Sem Fio', 'Mouse óptico sem fio com receptor USB', 129.90, 80),
    ('33333333-3333-3333-3333-333333333333', 'Monitor 27" 4K', 'Monitor IPS de 27 polegadas com resolução 4K', 2199.00, 15),
    ('44444444-4444-4444-4444-444444444444', 'Headset USB', 'Headset com microfone e conexão USB', 279.50, 0),
    ('55555555-5555-5555-5555-555555555555', 'Notebook Pro 16', 'Notebook de 16 polegadas com 32 GB de memória', 7499.00, 10),
    ('66666666-6666-6666-6666-666666666666', 'Webcam Full HD', 'Webcam 1080p com microfone embutido', 189.00, 30);
