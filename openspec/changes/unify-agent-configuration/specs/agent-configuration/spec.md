## ADDED Requirements

### Requirement: Agent is the composition root
The system SHALL represent an intelligent agent as the composition of identity, system prompt, model bindings, voice settings, memory policy, and Skill bindings.

#### Scenario: Load an agent configuration
- **WHEN** an authorized user opens an agent
- **THEN** the response includes its identity, prompt, model and voice bindings, memory policy, Skills, bound devices, and active version metadata through one agent-facing contract

#### Scenario: Save agent settings
- **WHEN** an authorized user saves changes from the agent editor
- **THEN** the system persists the changes as an agent draft without requiring the user to configure Skills from a device page

### Requirement: User-owned model resources are reusable
The system SHALL store model credentials as user-owned resources and SHALL expose only references, configuration overrides, and credential status in agent data.

#### Scenario: Select a configured model
- **WHEN** a user selects an available model resource for an agent
- **THEN** the agent stores the resource reference and non-secret overrides

#### Scenario: Configure a missing credential inline
- **WHEN** a selected model has missing credentials and the user opens credential configuration from the agent editor
- **THEN** the system saves the credential in the user resource scope and refreshes the agent's selectable model status without returning the secret value to the browser after save

### Requirement: Console separates composition from hardware
The system SHALL present agent composition, device hardware management, and reusable resource catalogs as separate concerns while making the agent editor the primary composition workflow.

#### Scenario: View a device's effective Skills
- **WHEN** a user opens a device
- **THEN** the console shows the Skills effective for that device and their availability reasons without presenting device-level Skill ownership controls
