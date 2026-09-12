package com.supplierhub.mock;

final class MockSupplierResponses {

	static final String A_HOTELS = """
			{
			  "items": [
			    {
			      "hotelCode": "A-10023",
			      "hotelName": "Riverside Hotel Seoul",
			      "roomTypes": [
			        {
			          "roomTypeCode": "DLX-TWN",
			          "roomTypeName": "Deluxe Twin",
			          "maxOccupancy": 2
			        }
			      ]
			    },
			    {
			      "hotelCode": "A-10044",
			      "hotelName": "Namsan Garden Stay",
			      "roomTypes": [
			        {
			          "roomTypeCode": "STD-DBL",
			          "roomTypeName": "Standard Double",
			          "maxOccupancy": 2
			        }
			      ]
			    }
			  ]
			}
			""";

	static final String A_AVAILABILITY = """
			{
			  "items": [
			    {
			      "hotelCode": "A-10023",
			      "hotelName": "Riverside Hotel Seoul",
			      "roomTypeCode": "DLX-TWN",
			      "roomTypeName": "Deluxe Twin",
			      "maxOccupancy": 2,
			      "breakfastIncluded": false,
			      "currency": "KRW",
			      "dailyRates": [
			        {
			          "date": "2026-09-01",
			          "remainingRooms": 3,
			          "nightlyRate": 120000,
			          "taxAmount": 12000
			        },
			        {
			          "date": "2026-09-02",
			          "remainingRooms": 1,
			          "nightlyRate": 150000,
			          "taxAmount": 15000
			        },
			        {
			          "date": "2026-09-03",
			          "remainingRooms": 5,
			          "nightlyRate": 120000,
			          "taxAmount": 12000
			        }
			      ]
			    },
			    {
			      "hotelCode": "A-10044",
			      "hotelName": "Namsan Garden Stay",
			      "roomTypeCode": "STD-DBL",
			      "roomTypeName": "Standard Double",
			      "maxOccupancy": 2,
			      "breakfastIncluded": false,
			      "currency": "KRW",
			      "dailyRates": [
			        {
			          "date": "2026-09-01",
			          "remainingRooms": 2,
			          "nightlyRate": 88000,
			          "taxAmount": 8800
			        },
			        {
			          "date": "2026-09-02",
			          "remainingRooms": 0,
			          "nightlyRate": 99000,
			          "taxAmount": 9900
			        },
			        {
			          "date": "2026-09-03",
			          "remainingRooms": 4,
			          "nightlyRate": 88000,
			          "taxAmount": 8800
			        }
			      ]
			    }
			  ]
			}
			""";

	static final String B_PROPERTIES = """
			{
			  "resultCode": "0000",
			  "resultMessage": "SUCCESS",
			  "data": {
			    "items": [
			      {
			        "propertyId": "B77120",
			        "propertyName": "Riverside Hotel Seoul",
			        "rooms": [
			          {
			            "roomId": "R-401",
			            "roomName": "Deluxe Twin Room",
			            "maxOccupancy": 2
			          }
			        ]
			      }
			    ]
			  }
			}
			""";

	static final String B_SEARCH = """
			{
			  "resultCode": "0000",
			  "resultMessage": "SUCCESS",
			  "data": {
			    "items": [
			      {
			        "propertyId": "B77120",
			        "propertyName": "Riverside Hotel Seoul",
			        "roomId": "R-401",
			        "roomName": "Deluxe Twin Room",
			        "maxOccupancy": 2,
			        "breakfastIncluded": true,
			        "currency": "KRW",
			        "totalPrice": 452000,
			        "taxIncluded": true,
			        "inventory": [
			          {
			            "date": "2026-09-01",
			            "remainingRooms": 3
			          },
			          {
			            "date": "2026-09-02",
			            "remainingRooms": 1
			          },
			          {
			            "date": "2026-09-03",
			            "remainingRooms": 5
			          }
			        ]
			      }
			    ]
			  }
			}
			""";

	static final String A_SERVICE_UNAVAILABLE = """
			{"error":"SERVICE_UNAVAILABLE","message":"temporarily unavailable"}
			""";

	static final String A_INVALID_SCENARIO = """
			{"error":"INVALID_PARAMETER","message":"unsupported X-Mock-Scenario"}
			""";

	static final String B_SERVICE_UNAVAILABLE = """
			{"resultCode":"E503","resultMessage":"TEMPORARILY_UNAVAILABLE","data":null}
			""";

	static final String B_INVALID_SCENARIO = """
			{"resultCode":"E400","resultMessage":"INVALID_PARAMETER","data":null}
			""";

	private MockSupplierResponses() {
	}

}