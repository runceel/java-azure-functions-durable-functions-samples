param location string
param token string
param tags object
param subnetId string
param dnsZoneIds string[]

resource workspace 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: 'log-${token}'
  location: location
  tags: tags
  properties: {
    sku: { name: 'PerGB2018' }
    retentionInDays: 30
    publicNetworkAccessForIngestion: 'Disabled'
    publicNetworkAccessForQuery: 'Enabled'
    features: { disableLocalAuth: true }
  }
}

resource insights 'Microsoft.Insights/components@2020-02-02' = {
  name: 'appi-${token}'
  location: location
  tags: tags
  kind: 'web'
  properties: {
    Application_Type: 'web'
    WorkspaceResourceId: workspace.id
    DisableLocalAuth: true
    publicNetworkAccessForIngestion: 'Disabled'
    publicNetworkAccessForQuery: 'Enabled'
  }
}

resource scope 'Microsoft.Insights/privateLinkScopes@2021-07-01-preview' = {
  name: 'ampls-${token}'
  location: 'global'
  tags: tags
  properties: {
    accessModeSettings: {
      ingestionAccessMode: 'PrivateOnly'
      queryAccessMode: 'PrivateOnly'
    }
  }
}

resource workspaceLink 'Microsoft.Insights/privateLinkScopes/scopedResources@2021-07-01-preview' = {
  parent: scope
  name: 'workspace'
  properties: { linkedResourceId: workspace.id }
}

resource insightsLink 'Microsoft.Insights/privateLinkScopes/scopedResources@2021-07-01-preview' = {
  parent: scope
  name: 'insights'
  properties: { linkedResourceId: insights.id }
}

module privateEndpoint './private-endpoint.bicep' = {
  params: {
    name: 'pe-monitor-${token}'
    location: location
    tags: tags
    resourceId: scope.id
    groupId: 'azuremonitor'
    subnetId: subnetId
    dnsZoneIds: dnsZoneIds
  }
  dependsOn: [ workspaceLink, insightsLink ]
}

output insightsName string = insights.name
output connectionString string = insights.properties.ConnectionString
