rootProject.name = "pim-service"

include(
    "domain",
    "application",
    "adapters:persistence-postgres",
    "adapters:outbox-kafka",
    "adapters:web",
    "bootstrap",
)
