param name string
param location string
param tags object
param subnetId string
param blobZoneId string
param queueZoneId string
param hostPrincipalId string = ''
param business bool = false

resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: name
  location: location
  tags: tags
  kind: 'StorageV2'
  sku: { name: 'Standard_LRS' }
  properties: {
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
    allowBlobPublicAccess: false
    allowSharedKeyAccess: false
    defaultToOAuthAuthentication: true
    publicNetworkAccess: 'Disabled'
    networkAcls: {
      bypass: 'None'
      defaultAction: 'Deny'
    }
  }
}

resource blobs 'Microsoft.Storage/storageAccounts/blobServices@2023-05-01' = {
  parent: storage
  name: 'default'
}

var containerNames = business ? [ 'batch-inputs', 'batch-pages' ] : [ 'deployment' ]
resource containers 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' = [for container in containerNames: {
  parent: blobs
  name: container
  properties: { publicAccess: 'None' }
}]

resource queues 'Microsoft.Storage/storageAccounts/queueServices@2023-05-01' = if (business) {
  parent: storage
  name: 'default'
}

resource inputQueue 'Microsoft.Storage/storageAccounts/queueServices/queues@2023-05-01' = if (business) {
  parent: queues
  name: 'http-check-file'
}

resource poisonQueue 'Microsoft.Storage/storageAccounts/queueServices/queues@2023-05-01' = if (business) {
  parent: queues
  name: 'http-check-file-poison'
}

resource hostAccess 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(hostPrincipalId)) {
  name: guid(storage.id, hostPrincipalId, 'host-blob-owner')
  scope: storage
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'b7e6dc6d-f1e8-4753-8033-0f276bb0955b')
    principalId: hostPrincipalId
    principalType: 'ServicePrincipal'
  }
}

module blobEndpoint './private-endpoint.bicep' = {
  params: {
    name: 'pe-${name}-blob'
    location: location
    tags: tags
    resourceId: storage.id
    groupId: 'blob'
    subnetId: subnetId
    dnsZoneIds: [ blobZoneId ]
  }
}

module queueEndpoint './private-endpoint.bicep' = if (business) {
  params: {
    name: 'pe-${name}-queue'
    location: location
    tags: tags
    resourceId: storage.id
    groupId: 'queue'
    subnetId: subnetId
    dnsZoneIds: [ queueZoneId ]
  }
}

output name string = storage.name
output blobUri string = storage.properties.primaryEndpoints.blob
output queueUri string = storage.properties.primaryEndpoints.queue
