@description('Exact scheduler hostname from its ARM endpoint; no constructed service hostname.')
param hostname string
param networkInterfaceName string
param virtualNetworkId string
param tags object

resource networkInterface 'Microsoft.Network/networkInterfaces@2024-05-01' existing = {
  name: networkInterfaceName
}

// An exact-host private zone overrides only this scheduler, not all schedulers in a region.
resource zone 'Microsoft.Network/privateDnsZones@2024-06-01' = {
  name: hostname
  location: 'global'
  tags: tags
}

resource address 'Microsoft.Network/privateDnsZones/A@2024-06-01' = {
  parent: zone
  name: '@'
  properties: {
    ttl: 300
    aRecords: [ { ipv4Address: networkInterface.properties.ipConfigurations[0].properties.privateIPAddress } ]
  }
}

resource link 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2024-06-01' = {
  parent: zone
  name: 'application'
  location: 'global'
  properties: {
    registrationEnabled: false
    virtualNetwork: { id: virtualNetworkId }
  }
}
