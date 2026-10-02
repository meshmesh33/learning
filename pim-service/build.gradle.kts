// Each module applies the shared `pim.java-conventions` plugin from buildSrc.
// Dependency direction (enforced by module boundaries and by ArchitectureTest):
//
//   bootstrap ──► adapters/* ──► application ──► domain
//
// domain has no dependencies at all; application depends only on domain.
