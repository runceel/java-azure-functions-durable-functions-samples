param location string
param token string
param tags object

@description('Object ID of the deploying identity that uses the DTS dashboard.')
param principalId string

resource scheduler 'Microsoft.DurableTask/schedulers@2026-02-01' = {
  name: 'dts-${token}'
  location: location
  tags: tags
  properties: {
    sku: { name: 'Consumption' }
    publicNetworkAccess: 'Enabled'
    ipAllowlist: [ '0.0.0.0/0' ]
  }
}

resource dashboardAccess 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(scheduler.id, principalId, 'durable-dashboard-contributor')
  scope: scheduler
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '0ad04412-c4d5-4796-b79c-f76d14c8d402')
    principalId: principalId
  }
}

output endpoint string = scheduler.properties.endpoint
