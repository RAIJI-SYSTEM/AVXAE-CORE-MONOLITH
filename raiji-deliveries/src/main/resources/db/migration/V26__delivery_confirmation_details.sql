-- Nome de quem efetivamente recebeu a encomenda.
ALTER TABLE deliveries
    ADD COLUMN confirmed_recipient_name VARCHAR(120);

-- Observação registrada pelo motorista no momento da entrega.
ALTER TABLE deliveries
    ADD COLUMN delivery_observation VARCHAR(500);