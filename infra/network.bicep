param location string
param token string
param tags object

var integrationPrefix = '10.80.0.0/26'
var privateEndpointPrefix = '10.80.1.0/24'

resource outbound 'Microsoft.Network/networkSecurityGroups@2024-05-01' = {
  name: 'nsg-functions-${token}'
  location: location
  tags: tags
  properties: {
    securityRules: [
      {
        name: 'AllowPrivateDependencies'
        properties: {
          priority: 100
          direction: 'Outbound'
          access: 'Allow'
          protocol: 'Tcp'
          sourceAddressPrefix: integrationPrefix
          sourcePortRange: '*'
          destinationAddressPrefix: privateEndpointPrefix
          destinationPortRanges: [ '443', '5432' ]
        }
      }
      {
        name: 'DenyOtherOutbound'
        properties: {
          priority: 4000
          direction: 'Outbound'
          access: 'Deny'
          protocol: '*'
          sourceAddressPrefix: '*'
          sourcePortRange: '*'
          destinationAddressPrefix: '*'
          destinationPortRange: '*'
        }
      }
    ]
  }
}

resource endpoints 'Microsoft.Network/networkSecurityGroups@2024-05-01' = {
  name: 'nsg-endpoints-${token}'
  location: location
  tags: tags
  properties: {
    securityRules: [
      {
        name: 'AllowFunctions'
        properties: {
          priority: 100
          direction: 'Inbound'
          access: 'Allow'
          protocol: 'Tcp'
          sourceAddressPrefix: integrationPrefix
          sourcePortRange: '*'
          destinationAddressPrefix: privateEndpointPrefix
          destinationPortRanges: [ '443', '5432' ]
        }
      }
      {
        name: 'DenyOtherInbound'
        properties: {
          priority: 4000
          direction: 'Inbound'
          access: 'Deny'
          protocol: '*'
          sourceAddressPrefix: '*'
          sourcePortRange: '*'
          destinationAddressPrefix: '*'
          destinationPortRange: '*'
        }
      }
      {
        name: 'DenyNewOutbound'
        properties: {
          priority: 4000
          direction: 'Outbound'
          access: 'Deny'
          protocol: '*'
          sourceAddressPrefix: '*'
          sourcePortRange: '*'
          destinationAddressPrefix: '*'
          destinationPortRange: '*'
        }
      }
    ]
  }
}

resource vnet 'Microsoft.Network/virtualNetworks@2024-05-01' = {
  name: 'vnet-${token}'
  location: location
  tags: tags
  properties: {
    addressSpace: { addressPrefixes: [ '10.80.0.0/16' ] }
    subnets: [
      {
        name: 'functions'
        properties: {
          addressPrefix: integrationPrefix
          networkSecurityGroup: { id: outbound.id }
          delegations: [
            {
              name: 'flex-consumption'
              properties: { serviceName: 'Microsoft.App/environments' }
            }
          ]
        }
      }
      {
        name: 'private-endpoints'
        properties: {
          addressPrefix: privateEndpointPrefix
          networkSecurityGroup: { id: endpoints.id }
          privateEndpointNetworkPolicies: 'Enabled'
        }
      }
    ]
  }
}

var zoneNames = [
  'privatelink.blob.${environment().suffixes.storage}'
  'privatelink.queue.${environment().suffixes.storage}'
  'privatelink.postgres.database.azure.com'
  'privatelink.monitor.azure.com'
  'privatelink.oms.opinsights.azure.com'
  'privatelink.ods.opinsights.azure.com'
  'privatelink.agentsvc.azure-automation.net'
  'privatelink.azurewebsites.net'
]

resource zones 'Microsoft.Network/privateDnsZones@2024-06-01' = [for zone in zoneNames: {
  name: zone
  location: 'global'
  tags: tags
}]

resource links 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2024-06-01' = [for (zone, index) in zoneNames: {
  parent: zones[index]
  name: 'application'
  location: 'global'
  properties: {
    registrationEnabled: false
    virtualNetwork: { id: vnet.id }
  }
}]

output virtualNetworkId string = vnet.id
output integrationSubnetId string = '${vnet.id}/subnets/functions'
output privateEndpointSubnetId string = '${vnet.id}/subnets/private-endpoints'
output blobZoneId string = zones[0].id
output queueZoneId string = zones[1].id
output postgresZoneId string = zones[2].id
output functionsZoneId string = zones[7].id
output monitorZoneIds string[] = [
  zones[3].id
  zones[4].id
  zones[5].id
  zones[6].id
  zones[0].id
]
