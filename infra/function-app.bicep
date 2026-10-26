param name string
param serviceName string
param location string
param tags object
param identityId string
param clientId string
param principalId string
param integrationSubnetId string
param privateEndpointSubnetId string
param storageName string
param blobZoneId string
param queueZoneId string
param functionsZoneId string
param insightsName string
param insightsConnectionString string
param settings object = {}

module storage './storage.bicep' = {
  params: {
    name: storageName
    location: location
    tags: tags
    subnetId: privateEndpointSubnetId
    blobZoneId: blobZoneId
    queueZoneId: queueZoneId
    hostPrincipalId: principalId
  }
}

resource insights 'Microsoft.Insights/components@2020-02-02' existing = {
  name: insightsName
}

resource telemetryAccess 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(insights.id, principalId, 'monitoring-publisher')
  scope: insights
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '3913510d-42f4-4e42-8a64-420c390055eb')
    principalId: principalId
    principalType: 'ServicePrincipal'
  }
}

resource plan 'Microsoft.Web/serverfarms@2024-11-01' = {
  name: 'plan-${name}'
  location: location
  tags: tags
  kind: 'functionapp'
  sku: {
    name: 'FC1'
    tier: 'FlexConsumption'
  }
  properties: { reserved: true }
}

var appSettings = union({
  AzureWebJobsStorage__blobServiceUri: storage.outputs.blobUri
  AzureWebJobsStorage__credential: 'managedidentity'
  AzureWebJobsStorage__clientId: clientId
  AZURE_CLIENT_ID: clientId
  APPLICATIONINSIGHTS_CONNECTION_STRING: insightsConnectionString
  APPLICATIONINSIGHTS_AUTHENTICATION_STRING: 'Authorization=AAD;ClientId=${clientId}'
  JAVA_OPTS: '-javaagent:/home/site/wwwroot/applicationinsights-agent.jar -Dotel.instrumentation.grpc.enabled=false'
}, settings)

resource app 'Microsoft.Web/sites@2024-11-01' = {
  name: name
  location: location
  tags: union(tags, { 'azd-service-name': serviceName })
  kind: 'functionapp,linux'
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: { '${identityId}': {} }
  }
  properties: {
    serverFarmId: plan.id
    httpsOnly: true
    publicNetworkAccess: 'Enabled'
    virtualNetworkSubnetId: integrationSubnetId
    functionAppConfig: {
      runtime: {
        name: 'java'
        version: '11'
      }
      deployment: {
        storage: {
          type: 'blobContainer'
          value: '${storage.outputs.blobUri}deployment'
          authentication: {
            type: 'UserAssignedIdentity'
            userAssignedIdentityResourceId: identityId
          }
        }
      }
      scaleAndConcurrency: {
        instanceMemoryMB: 2048
        maximumInstanceCount: 40
      }
    }
    siteConfig: {
      minTlsVersion: '1.2'
      scmMinTlsVersion: '1.2'
      ftpsState: 'Disabled'
      appSettings: map(items(appSettings), setting => {
        name: setting.key
        value: setting.value
      })
    }
  }
  dependsOn: [ telemetryAccess ]
}

resource scmAuthentication 'Microsoft.Web/sites/basicPublishingCredentialsPolicies@2024-11-01' = {
  parent: app
  name: 'scm'
  properties: { allow: false }
}

// Trigger synchronization calls the app's own hostname, which must stay private inside the VNet.
module endpoint './private-endpoint.bicep' = {
  name: '${name}-private-endpoint'
  params: {
    name: 'pe-${name}'
    location: location
    tags: tags
    resourceId: app.id
    groupId: 'sites'
    subnetId: privateEndpointSubnetId
    dnsZoneIds: [ functionsZoneId ]
  }
}

resource ftpAuthentication 'Microsoft.Web/sites/basicPublishingCredentialsPolicies@2024-11-01' = {
  parent: app
  name: 'ftp'
  properties: { allow: false }
}

output name string = app.name
output url string = 'https://${app.properties.defaultHostName}'
