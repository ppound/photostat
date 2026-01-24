"""OpenSearch client wrapper for PhotoVault."""

from typing import Dict, List, Any, Optional, Tuple
from opensearchpy import OpenSearch, RequestsHttpConnection
from opensearchpy.exceptions import NotFoundError, ConnectionError as OSConnectionError

from ..models.image_metadata import ImageMetadata


class OpenSearchService:
    """Service for interacting with OpenSearch."""

    INDEX_MAPPING = {
        "mappings": {
            "properties": {
                "file_path": {"type": "keyword"},
                "file_name": {
                    "type": "text",
                    "fields": {"keyword": {"type": "keyword"}}
                },
                "file_size": {"type": "long"},
                "file_type": {"type": "keyword"},
                "date_indexed": {"type": "date"},
                "date_taken": {"type": "date"},
                "camera_make": {"type": "keyword"},
                "camera_model": {"type": "keyword"},
                "lens_model": {"type": "keyword"},
                "focal_length": {"type": "float"},
                "aperture": {"type": "float"},
                "shutter_speed": {"type": "keyword"},
                "iso": {"type": "integer"},
                "exposure_mode": {"type": "keyword"},
                "white_balance": {"type": "keyword"},
                "flash": {"type": "keyword"},
                "image_width": {"type": "integer"},
                "image_height": {"type": "integer"},
                "orientation": {"type": "keyword"},
                "gps_latitude": {"type": "float"},
                "gps_longitude": {"type": "float"},
                "gps_location": {"type": "geo_point"},
                "artist": {"type": "text"},
                "copyright": {"type": "text"},
                "software": {"type": "keyword"},
                "all_exif": {"type": "object", "enabled": False}
            }
        },
        "settings": {
            "number_of_shards": 1,
            "number_of_replicas": 0
        }
    }

    def __init__(
        self,
        host: str = "localhost",
        port: int = 9200,
        username: Optional[str] = None,
        password: Optional[str] = None,
        use_ssl: bool = False,
        index_name: str = "photovault-images"
    ):
        """Initialize the OpenSearch service."""
        self.host = host
        self.port = port
        self.username = username
        self.password = password
        self.use_ssl = use_ssl
        self.index_name = index_name
        self._client: Optional[OpenSearch] = None

    def connect(self) -> bool:
        """Establish connection to OpenSearch."""
        try:
            auth = None
            if self.username and self.password:
                auth = (self.username, self.password)

            self._client = OpenSearch(
                hosts=[{'host': self.host, 'port': self.port}],
                http_auth=auth,
                use_ssl=self.use_ssl,
                verify_certs=False,
                ssl_show_warn=False,
                connection_class=RequestsHttpConnection
            )

            # Test connection
            info = self._client.info()
            return True

        except Exception as e:
            print(f"Failed to connect to OpenSearch: {e}")
            self._client = None
            return False

    def disconnect(self):
        """Close the OpenSearch connection."""
        if self._client:
            self._client.close()
            self._client = None

    def is_connected(self) -> bool:
        """Check if connected to OpenSearch."""
        if not self._client:
            return False
        try:
            self._client.ping()
            return True
        except Exception:
            return False

    def test_connection(self) -> Tuple[bool, str]:
        """Test the OpenSearch connection and return status message."""
        try:
            if not self._client:
                self.connect()

            if not self._client:
                return False, "Failed to create client"

            info = self._client.info()
            version = info.get('version', {}).get('number', 'unknown')
            cluster_name = info.get('cluster_name', 'unknown')
            return True, f"Connected to OpenSearch {version} (cluster: {cluster_name})"

        except OSConnectionError as e:
            return False, f"Connection failed: {str(e)}"
        except Exception as e:
            return False, f"Error: {str(e)}"

    def ensure_index(self) -> bool:
        """Ensure the index exists with proper mapping."""
        if not self._client:
            return False

        try:
            if not self._client.indices.exists(index=self.index_name):
                self._client.indices.create(
                    index=self.index_name,
                    body=self.INDEX_MAPPING
                )
            return True
        except Exception as e:
            print(f"Failed to create index: {e}")
            return False

    def delete_index(self) -> bool:
        """Delete the index."""
        if not self._client:
            return False

        try:
            if self._client.indices.exists(index=self.index_name):
                self._client.indices.delete(index=self.index_name)
            return True
        except Exception as e:
            print(f"Failed to delete index: {e}")
            return False

    def index_document(self, metadata: ImageMetadata) -> bool:
        """Index a single image metadata document."""
        if not self._client:
            return False

        try:
            doc = metadata.to_opensearch_doc()
            # Use file_path as document ID for idempotency
            doc_id = metadata.file_path.replace('/', '_').replace('\\', '_')

            self._client.index(
                index=self.index_name,
                id=doc_id,
                body=doc,
                refresh=False
            )
            return True
        except Exception as e:
            print(f"Failed to index document: {e}")
            return False

    def index_documents_bulk(self, metadata_list: List[ImageMetadata]) -> Tuple[int, int]:
        """Bulk index multiple documents. Returns (success_count, error_count)."""
        if not self._client or not metadata_list:
            return 0, len(metadata_list)

        try:
            actions = []
            for metadata in metadata_list:
                doc = metadata.to_opensearch_doc()
                doc_id = metadata.file_path.replace('/', '_').replace('\\', '_')

                actions.append({"index": {"_index": self.index_name, "_id": doc_id}})
                actions.append(doc)

            if not actions:
                return 0, 0

            response = self._client.bulk(body=actions, refresh=False)

            success = 0
            errors = 0
            for item in response.get('items', []):
                if item.get('index', {}).get('status') in (200, 201):
                    success += 1
                else:
                    errors += 1

            return success, errors

        except Exception as e:
            print(f"Bulk indexing failed: {e}")
            return 0, len(metadata_list)

    def refresh_index(self):
        """Refresh the index to make documents searchable."""
        if self._client:
            try:
                self._client.indices.refresh(index=self.index_name)
            except Exception:
                pass

    def delete_by_path(self, file_path: str) -> bool:
        """Delete a document by file path."""
        if not self._client:
            return False

        try:
            doc_id = file_path.replace('/', '_').replace('\\', '_')
            self._client.delete(index=self.index_name, id=doc_id, refresh=True)
            return True
        except NotFoundError:
            return True  # Already deleted
        except Exception as e:
            print(f"Failed to delete document: {e}")
            return False

    def delete_by_directory(self, directory: str) -> int:
        """Delete all documents from a directory. Returns count of deleted docs."""
        if not self._client:
            return 0

        try:
            # Normalize path for matching
            normalized = directory.replace('\\', '/').rstrip('/')

            response = self._client.delete_by_query(
                index=self.index_name,
                body={
                    "query": {
                        "wildcard": {
                            "file_path": f"{normalized}/*"
                        }
                    }
                },
                refresh=True
            )
            return response.get('deleted', 0)

        except Exception as e:
            print(f"Failed to delete by directory: {e}")
            return 0

    def search(
        self,
        query_text: Optional[str] = None,
        filters: Optional[Dict[str, Any]] = None,
        from_: int = 0,
        size: int = 50,
        sort_by: str = "date_taken",
        sort_order: str = "desc"
    ) -> Dict[str, Any]:
        """
        Search for images with optional filters.

        Args:
            query_text: Full-text search query
            filters: Dictionary of field filters
            from_: Pagination offset
            size: Number of results to return
            sort_by: Field to sort by
            sort_order: 'asc' or 'desc'

        Returns:
            Dictionary with 'hits', 'total', and 'aggregations'
        """
        if not self._client:
            return {'hits': [], 'total': 0, 'aggregations': {}}

        try:
            must_clauses = []
            filter_clauses = []

            # Full-text search
            if query_text:
                must_clauses.append({
                    "multi_match": {
                        "query": query_text,
                        "fields": ["file_name^2", "camera_make", "camera_model",
                                   "lens_model", "artist", "copyright"],
                        "type": "best_fields",
                        "fuzziness": "AUTO"
                    }
                })

            # Apply filters
            if filters:
                # Camera make
                if filters.get('camera_make'):
                    filter_clauses.append({"term": {"camera_make": filters['camera_make']}})

                # Camera model
                if filters.get('camera_model'):
                    filter_clauses.append({"term": {"camera_model": filters['camera_model']}})

                # Lens model
                if filters.get('lens_model'):
                    filter_clauses.append({"term": {"lens_model": filters['lens_model']}})

                # File type
                if filters.get('file_type'):
                    filter_clauses.append({"term": {"file_type": filters['file_type']}})

                # Date range
                if filters.get('date_from') or filters.get('date_to'):
                    date_range = {}
                    if filters.get('date_from'):
                        date_range['gte'] = filters['date_from']
                    if filters.get('date_to'):
                        date_range['lte'] = filters['date_to']
                    filter_clauses.append({"range": {"date_taken": date_range}})

                # ISO range
                if filters.get('iso_min') is not None or filters.get('iso_max') is not None:
                    iso_range = {}
                    if filters.get('iso_min') is not None:
                        iso_range['gte'] = filters['iso_min']
                    if filters.get('iso_max') is not None:
                        iso_range['lte'] = filters['iso_max']
                    filter_clauses.append({"range": {"iso": iso_range}})

                # Aperture range
                if filters.get('aperture_min') is not None or filters.get('aperture_max') is not None:
                    aperture_range = {}
                    if filters.get('aperture_min') is not None:
                        aperture_range['gte'] = filters['aperture_min']
                    if filters.get('aperture_max') is not None:
                        aperture_range['lte'] = filters['aperture_max']
                    filter_clauses.append({"range": {"aperture": aperture_range}})

                # Focal length range
                if filters.get('focal_min') is not None or filters.get('focal_max') is not None:
                    focal_range = {}
                    if filters.get('focal_min') is not None:
                        focal_range['gte'] = filters['focal_min']
                    if filters.get('focal_max') is not None:
                        focal_range['lte'] = filters['focal_max']
                    filter_clauses.append({"range": {"focal_length": focal_range}})

            # Build query
            if must_clauses or filter_clauses:
                query = {
                    "bool": {
                        "must": must_clauses if must_clauses else [{"match_all": {}}],
                        "filter": filter_clauses
                    }
                }
            else:
                query = {"match_all": {}}

            # Aggregations for facets
            aggs = {
                "camera_make": {
                    "terms": {"field": "camera_make", "size": 50}
                },
                "camera_model": {
                    "terms": {"field": "camera_model", "size": 50}
                },
                "lens_model": {
                    "terms": {"field": "lens_model", "size": 50}
                },
                "file_type": {
                    "terms": {"field": "file_type", "size": 20}
                },
                "year_month": {
                    "date_histogram": {
                        "field": "date_taken",
                        "calendar_interval": "month",
                        "format": "yyyy-MM",
                        "min_doc_count": 1
                    }
                },
                "iso_ranges": {
                    "range": {
                        "field": "iso",
                        "ranges": [
                            {"key": "100-400", "from": 100, "to": 401},
                            {"key": "400-800", "from": 400, "to": 801},
                            {"key": "800-1600", "from": 800, "to": 1601},
                            {"key": "1600-3200", "from": 1600, "to": 3201},
                            {"key": "3200-6400", "from": 3200, "to": 6401},
                            {"key": "6400+", "from": 6400}
                        ]
                    }
                }
            }

            # Handle sort
            sort = []
            if sort_by:
                sort.append({sort_by: {"order": sort_order, "missing": "_last"}})
            sort.append({"_score": {"order": "desc"}})

            # Execute search
            response = self._client.search(
                index=self.index_name,
                body={
                    "query": query,
                    "aggs": aggs,
                    "from": from_,
                    "size": size,
                    "sort": sort,
                    "_source": {
                        "excludes": ["all_exif"]
                    }
                }
            )

            # Parse results
            hits = []
            for hit in response['hits']['hits']:
                source = hit['_source']
                source['_id'] = hit['_id']
                source['_score'] = hit.get('_score')
                hits.append(ImageMetadata.from_opensearch_doc(source))

            total = response['hits']['total']
            if isinstance(total, dict):
                total = total.get('value', 0)

            return {
                'hits': hits,
                'total': total,
                'aggregations': response.get('aggregations', {})
            }

        except Exception as e:
            print(f"Search failed: {e}")
            return {'hits': [], 'total': 0, 'aggregations': {}}

    def get_document(self, file_path: str) -> Optional[ImageMetadata]:
        """Get a single document by file path."""
        if not self._client:
            return None

        try:
            doc_id = file_path.replace('/', '_').replace('\\', '_')
            response = self._client.get(index=self.index_name, id=doc_id)
            return ImageMetadata.from_opensearch_doc(response['_source'])
        except NotFoundError:
            return None
        except Exception as e:
            print(f"Failed to get document: {e}")
            return None

    def get_indexed_count(self) -> int:
        """Get total count of indexed documents."""
        if not self._client:
            return 0

        try:
            response = self._client.count(index=self.index_name)
            return response.get('count', 0)
        except Exception:
            return 0

    def get_indexed_directories(self) -> List[str]:
        """Get list of unique directories that have been indexed."""
        if not self._client:
            return []

        try:
            # Get unique directory paths using aggregation
            response = self._client.search(
                index=self.index_name,
                body={
                    "size": 0,
                    "aggs": {
                        "directories": {
                            "terms": {
                                "field": "file_path",
                                "size": 10000
                            }
                        }
                    }
                }
            )

            # Extract unique parent directories
            directories = set()
            for bucket in response.get('aggregations', {}).get('directories', {}).get('buckets', []):
                path = bucket['key']
                # Get parent directory
                if '/' in path:
                    parent = '/'.join(path.split('/')[:-1])
                elif '\\' in path:
                    parent = '\\'.join(path.split('\\')[:-1])
                else:
                    continue
                directories.add(parent)

            return sorted(directories)

        except Exception:
            return []
