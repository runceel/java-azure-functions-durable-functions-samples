param name string
param location string
param tags object
param resourceId string
param groupId string
param subnetId string
param dnsZoneIds string[]

resource endpoint 'Microsoft.Network/privateEndpoints@2024-05-01' = {
  name: name
  location: location
  tags: tags
  properties: {
    subnet: { id: subnetId }
    privateLinkServiceConnections: [
      {
        name: name
        properties: {
          privateLinkServiceId: resourceId
          groupIds: [ groupId ]
        }
      }
    ]
  }
}

resource dns 'Microsoft.Network/privateEndpoints/privateDnsZoneGroups@2024-05-01' = {
  parent: endpoint
  name: 'default'
  properties: {
    privateDnsZoneConfigs: [for (zone, index) in dnsZoneIds: {
      name: 'zone-${index}'
      properties: { privateDnsZoneId: zone }
    }]
  }
}
