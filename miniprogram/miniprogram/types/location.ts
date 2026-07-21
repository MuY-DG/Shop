export interface AmapClientConfig {
  enabled: boolean;
  miniProgramKey: string;
}

export interface AddressLocationSelection {
  province: string;
  city: string;
  district: string;
  detailAddress: string;
  formattedAddress: string;
  adcode: string;
  longitude: number;
  latitude: number;
  poiName: string;
}
