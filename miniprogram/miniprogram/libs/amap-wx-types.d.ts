export interface AMapFailure {
  errCode: string | number;
  errMsg: unknown;
}

export interface AMapAddressComponent {
  province?: string | string[];
  city?: string | string[];
  district?: string | string[];
  adcode?: string;
  township?: string | string[];
  streetNumber?: {
    street?: string | string[];
    number?: string | string[];
  };
}

export interface AMapPoi {
  id?: string;
  name?: string;
  address?: string | string[];
  location?: string | string[];
  pname?: string;
  cityname?: string;
  adname?: string;
  type?: string;
  distance?: string;
}

export interface AMapRegeocodeData {
  formatted_address?: string;
  addressComponent?: AMapAddressComponent;
  pois?: AMapPoi[];
}

export interface AMapRegeoMarker {
  longitude: number;
  latitude: number;
  name: string;
  desc: string;
  regeocodeData: AMapRegeocodeData;
}

export interface AMapInputTip extends AMapPoi {
  district?: string;
  adcode?: string;
}

export interface AMapClient {
  getRegeo(options: {
    location?: string;
    success: (data: AMapRegeoMarker[]) => void;
    fail: (error: AMapFailure) => void;
  }): void;

  getPoiAround(options: {
    location?: string;
    querykeywords?: string;
    querytypes?: string;
    pageNumber?: number;
    pageSize?: number;
    success: (data: { poisData?: AMapPoi[] }) => void;
    fail: (error: AMapFailure) => void;
  }): void;

  getPoiKeywords(options: {
    keywords: string;
    city?: string;
    citylimit?: boolean;
    pageNumber?: number;
    pageSize?: number;
    success: (data: { poisData?: AMapPoi[]; count?: number }) => void;
    fail: (error: AMapFailure) => void;
  }): void;

  getInputtips(options: {
    keywords: string;
    location?: string;
    city?: string;
    citylimit?: boolean;
    type?: string;
    success: (data: { tips?: AMapInputTip[] }) => void;
    fail: (error: AMapFailure) => void;
  }): void;
}
