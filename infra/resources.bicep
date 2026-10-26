param location string
param token string
param environmentName string
param principalId string

var tags = { 'azd-env-name': environmentName }
var services = [ 'http-check-file', 'durable-functions', 'durable-csv-batch', 'db-initializer' ]
var businessStorageName = 'stdata${token}'

resource identities 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = [for service in services: {
  name: 'id-${service}-${token}'
  location: location
  tags: tags
}]

module network './network.bicep' = {
  params: {
    location: location
    token: token
    tags: tags
  }
}

module monitoring './monitoring.bicep' = {
  params: {
    location: location
    token: token
    tags: tags
    subnetId: network.outputs.privateEndpointSubnetId
    dnsZoneIds: network.outputs.monitorZoneIds
  }
}

module businessStorage './storage.bicep' = {
  params: {
    name: businessStorageName
    location: location
    tags: tags
    business: true
    subnetId: network.outputs.privateEndpointSubnetId
    blobZoneId: network.outputs.blobZoneId
    queueZoneId: network.outputs.queueZoneId
  }
}

resource dataAccount 'Microsoft.Storage/storageAccounts@2023-05-01' existing = {
  name: businessStorageName
}
resource blobs 'Microsoft.Storage/storageAccounts/blobServices@2023-05-01' existing = {
  parent: dataAccount
  name: 'default'
}
resource inputContainer 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' existing = {
  parent: blobs
  name: 'batch-inputs'
}
resource pageContainer 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' existing = {
  parent: blobs
  name: 'batch-pages'
}
resource queues 'Microsoft.Storage/storageAccounts/queueServices@2023-05-01' existing = {
  parent: dataAccount
  name: 'default'
}

resource inputAccess 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for index in [ 2, 3 ]: {
  name: guid(inputContainer.id, identities[index].id, 'blob-contributor')
  scope: inputContainer
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'ba92f5b4-2d11-453d-a403-e96b0029c9fe')
    principalId: identities[index].properties.principalId
    principalType: 'ServicePrincipal'
  }
  dependsOn: [ businessStorage ]
}]

resource pageAccess 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(pageContainer.id, identities[2].id, 'blob-contributor')
  scope: pageContainer
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'ba92f5b4-2d11-453d-a403-e96b0029c9fe')
    principalId: identities[2].properties.principalId
    principalType: 'ServicePrincipal'
  }
  dependsOn: [ businessStorage ]
}

resource queueAccess 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(queues.id, identities[0].id, 'queue-contributor')
  scope: queues
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '974c5e8b-45b9-4653-ba55-5f855dd0fb88')
    principalId: identities[0].properties.principalId
    principalType: 'ServicePrincipal'
  }
  dependsOn: [ businessStorage ]
}

module postgres './postgres.bicep' = {
  params: {
    location: location
    token: token
    tags: tags
    initializerObjectId: identities[3].properties.principalId
    initializerName: identities[3].name
    subnetId: network.outputs.privateEndpointSubnetId
    dnsZoneId: network.outputs.postgresZoneId
  }
}

module schedulerConfiguration './scheduler.bicep' = {
  params: {
    location: location
    token: token
    tags: tags
    principalId: principalId
  }
}

resource scheduler 'Microsoft.DurableTask/schedulers@2026-02-01' existing = {
  name: 'dts-${token}'
}

var hubNames = [ 'default', 'workshop' ]
resource hubs 'Microsoft.DurableTask/schedulers/taskHubs@2026-02-01' = [for hub in hubNames: {
  parent: scheduler
  name: hub
  dependsOn: [ schedulerConfiguration ]
}]

resource schedulerAccess 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for (hub, index) in hubNames: {
  name: guid(hubs[index].id, identities[index + 1].id, 'durable-contributor')
  scope: hubs[index]
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '0ad04412-c4d5-4796-b79c-f76d14c8d402')
    principalId: identities[index + 1].properties.principalId
    principalType: 'ServicePrincipal'
  }
}]

resource schedulerEndpoint 'Microsoft.Network/privateEndpoints@2024-05-01' = {
  name: 'pe-scheduler-${token}'
  location: location
  tags: tags
  properties: {
    subnet: { id: network.outputs.privateEndpointSubnetId }
    privateLinkServiceConnections: [
      {
        name: 'scheduler'
        properties: {
          privateLinkServiceId: scheduler.id
          groupIds: [ 'scheduler' ]
        }
      }
    ]
  }
  dependsOn: [ schedulerConfiguration ]
}

module schedulerDns './scheduler-dns.bicep' = {
  params: {
    hostname: replace(replace(schedulerConfiguration.outputs.endpoint, 'https://', ''), '/', '')
    networkInterfaceName: last(split(schedulerEndpoint.properties.networkInterfaces[0].id, '/'))
    virtualNetworkId: network.outputs.virtualNetworkId
    tags: tags
  }
}

var settings = [
  {
    QueueStorage__queueServiceUri: businessStorage.outputs.queueUri
    QueueStorage__credential: 'managedidentity'
    QueueStorage__clientId: identities[0].properties.clientId
  }
  {
    DURABLE_TASK_SCHEDULER_CONNECTION_STRING: 'Endpoint=${schedulerConfiguration.outputs.endpoint};TaskHub=default;Authentication=ManagedIdentity;ClientID=${identities[1].properties.clientId}'
  }
  {
    DURABLE_TASK_SCHEDULER_CONNECTION_STRING: 'Endpoint=${schedulerConfiguration.outputs.endpoint};TaskHub=workshop;Authentication=ManagedIdentity;ClientID=${identities[2].properties.clientId}'
    AZURE_STORAGE_AUTHENTICATION: 'managedidentity'
    AZURE_STORAGE_BLOB_ENDPOINT: businessStorage.outputs.blobUri
    DB_AUTHENTICATION: 'managedidentity'
    DB_JDBC_URL: postgres.outputs.jdbcUrl
    DB_USERNAME: 'batch_runtime'
  }
  {
    DB_JDBC_URL: postgres.outputs.jdbcUrl
    DB_ADMIN_JDBC_URL: postgres.outputs.adminJdbcUrl
    DB_USERNAME: identities[3].name
    DB_RUNTIME_OBJECT_ID: identities[2].properties.principalId
    AZURE_STORAGE_BLOB_ENDPOINT: businessStorage.outputs.blobUri
  }
]

module functions './function-app.bicep' = [for (service, index) in services: {
  params: {
    name: 'func-${service}-${token}'
    serviceName: service
    location: location
    tags: tags
    identityId: identities[index].id
    clientId: identities[index].properties.clientId
    principalId: identities[index].properties.principalId
    integrationSubnetId: network.outputs.integrationSubnetId
    privateEndpointSubnetId: network.outputs.privateEndpointSubnetId
    storageName: 'sthost${index}${token}'
    blobZoneId: network.outputs.blobZoneId
    queueZoneId: network.outputs.queueZoneId
    functionsZoneId: network.outputs.functionsZoneId
    insightsName: monitoring.outputs.insightsName
    insightsConnectionString: monitoring.outputs.connectionString
    settings: settings[index]
  }
  dependsOn: [ inputAccess, pageAccess, queueAccess, schedulerAccess, schedulerDns ]
}]

output functionNames string[] = [for (service, index) in services: functions[index].outputs.name]
output functionUrls string[] = [for (service, index) in services: functions[index].outputs.url]
output initializerName string = functions[3].outputs.name
