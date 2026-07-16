# Network package

Owns versioned payload registration, bounded codecs and logical-side handlers. Client payloads are untrusted: validate placement UUID, loaded chunk, reach/permission, operation freshness and every value before world access. Durable mutations execute on the server and publish validated state.
