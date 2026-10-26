targetScope = 'subscription'

@minLength(1)
@maxLength(32)
param environmentName string

@description('Choose a region supporting Java Flex Consumption, DTS Private Link, and PostgreSQL.')
@metadata({ azd: { type: 'location' } })
param location string

@description('Object ID of the deploying identity; azd supplies AZURE_PRINCIPAL_ID.')
param principalId string

var token = uniqueString(subscription().id, environmentName, location)

resource resourceGroup 'Microsoft.Resources/resourceGroups@2024-03-01' = {
  name: 'rg-${environmentName}'
  location: location
  tags: { 'azd-env-name': environmentName }
}

module application './resources.bicep' = {
  scope: resourceGroup
  params: {
    location: location
    token: token
    environmentName: environmentName
    principalId: principalId
  }
}

output AZURE_RESOURCE_GROUP string = resourceGroup.name
output AZURE_LOCATION string = location
output AZURE_TENANT_ID string = tenant().tenantId
output DB_INITIALIZER_NAME string = application.outputs.initializerName
output SERVICE_HTTP_CHECK_FILE_NAME string = application.outputs.functionNames[0]
output SERVICE_DURABLE_FUNCTIONS_NAME string = application.outputs.functionNames[1]
output SERVICE_DURABLE_CSV_BATCH_NAME string = application.outputs.functionNames[2]
output SERVICE_DB_INITIALIZER_NAME string = application.outputs.functionNames[3]
output HTTP_CHECK_FILE_URL string = application.outputs.functionUrls[0]
output DURABLE_FUNCTIONS_URL string = application.outputs.functionUrls[1]
output DURABLE_CSV_BATCH_URL string = application.outputs.functionUrls[2]
